package dev.fmangoni.forge.security.layer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.osv.OsvClient;
import dev.fmangoni.forge.security.osv.model.OsvQuery;
import dev.fmangoni.forge.security.osv.model.OsvVuln;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependencies with published vulnerabilities, for each stack the project uses.
 *
 * <p>npm is asked with {@code npm audit}, which ships with npm. Maven and Python dependencies are
 * listed and looked up in OSV. Only what ships is checked: Maven's test scope is left out, because a
 * vulnerable test library is not in the thing anyone runs.
 *
 * <p>Serious means critical or high <em>and</em> a fixed version exists. A vulnerability with no
 * fix is still reported, but a repair turn cannot upgrade to a version that does not exist, and
 * sending an agent to try buys a session of workarounds nobody asked for.
 */
public final class DependencyAudit {

    public static final String LAYER = "dependencies";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code group:artifact:type[:classifier]:version:scope}, as dependency:list writes it. */
    private static final Pattern MAVEN_LINE = Pattern.compile(
            "^\\s*([\\w.\\-]+):([\\w.\\-]+):[\\w\\-]+(?::[\\w.\\-]+)?:([\\w.\\-]+):(compile|runtime|provided|system|test)\\b");

    /** {@code name==1.2.3}, optionally with extras. Unpinned lines cannot be looked up. */
    private static final Pattern PINNED = Pattern.compile(
            "^\\s*([A-Za-z0-9][A-Za-z0-9._\\-]*)(?:\\[[^]]*])?\\s*==\\s*([A-Za-z0-9.!+_\\-]+)\\s*(?:[;#].*)?$");

    private final Path repo;
    private final Path workDir;
    private final Shell shell;
    private final OsvClient osv;

    public DependencyAudit(Path repo, Path workDir, Shell shell, OsvClient osv) {
        this.repo = repo;
        this.workDir = workDir;
        this.shell = shell;
        this.osv = osv;
    }

    public LayerResult run() {
        List<SecurityFinding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean anything = false;
        try {
            if (Files.isRegularFile(repo.resolve("package.json"))) {
                anything = true;
                notes.add(npm(findings));
            }
            if (Files.isRegularFile(repo.resolve("pom.xml"))) {
                anything = true;
                notes.add(maven(findings));
            }
            List<Path> requirements = requirementFiles();
            if (!requirements.isEmpty()) {
                anything = true;
                notes.add(python(requirements, findings));
            }
        } catch (IOException e) {
            return LayerResult.failed(LAYER, "could not reach the vulnerability database: " + e.getMessage());
        }
        if (!anything) {
            return LayerResult.skipped(LAYER, "no package.json, pom.xml or requirements file");
        }
        return LayerResult.ran(LAYER, String.join("; ", notes), findings);
    }

    // ------------------------------------------------------------------ npm

    private String npm(List<SecurityFinding> findings) throws IOException {
        if (!Files.isRegularFile(repo.resolve("package-lock.json"))) {
            return "npm: no package-lock.json, so there is no exact tree to audit";
        }
        Files.createDirectories(workDir);
        Path out = workDir.resolve("npm-audit.json");
        Files.deleteIfExists(out);
        // Written to a file: an audit of a large tree is bigger than the output Forge keeps in memory,
        // and half a JSON document parses as nothing.
        ProcResult result = shell.run("npm-audit", "npm audit --json > \"" + out + "\"");
        if (Shell.missing(result)) {
            return "npm: not installed";
        }
        if (!Files.isRegularFile(out) || Files.size(out) == 0) {
            return "npm audit produced nothing (exit " + result.exitCode() + ")";
        }
        JsonNode audit = JSON.readTree(Files.readString(out));
        if (audit.has("error")) {
            return "npm audit failed: " + audit.path("error").path("summary").asText("unknown error");
        }
        Set<String> shipped = shippedPackages();
        int count = 0;
        for (JsonNode vuln : audit.path("vulnerabilities")) {
            count++;
            String severity = npmSeverity(vuln.path("severity").asText(""));
            JsonNode fix = vuln.path("fixAvailable");
            boolean fixable = fix.isObject() || fix.asBoolean(false);
            String name = vuln.path("name").asText();
            boolean ships = shipped == null || shipped.contains(name);
            findings.add(new SecurityFinding(LAYER, severity,
                    name + " has a known vulnerability" + (vuln.path("isDirect").asBoolean() ? "" : " (transitive)")
                            + (ships ? "" : " (development dependency)"),
                    "npm: " + name + " " + vuln.path("range").asText(""),
                    advisories(vuln) + (fixable ? "; a fixed version is available" : "; no fix available yet")
                            + (ships ? "" : "; used to build or test, not shipped to users"),
                    ships && fixable && isSevere(severity)));
        }
        return "npm: " + count + " vulnerable package(s)";
    }

    /**
     * Packages with vulnerabilities in the production tree, from {@code npm audit --omit=dev}.
     *
     * <p>Only those are serious. Pink Slip's six advisories were all in vitest, playwright and the vite
     * dev server — the tools that build and test it, none of which reaches a player — and a repair turn
     * spent upgrading test tooling fixes nothing anyone using the game is exposed to. They are still
     * reported. Null when the production audit could not be read, in which case everything counts.
     */
    private Set<String> shippedPackages() throws IOException {
        Path out = workDir.resolve("npm-audit-production.json");
        Files.deleteIfExists(out);
        shell.run("npm-audit-production", "npm audit --omit=dev --json > \"" + out + "\"");
        if (!Files.isRegularFile(out) || Files.size(out) == 0) {
            return null;
        }
        JsonNode production = JSON.readTree(Files.readString(out));
        if (production.has("error")) {
            return null;
        }
        Set<String> names = new HashSet<>();
        production.path("vulnerabilities").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String advisories(JsonNode vuln) {
        List<String> titles = new ArrayList<>();
        for (JsonNode via : vuln.path("via")) {
            if (via.isObject() && via.hasNonNull("title")) {
                titles.add(via.path("title").asText());
            }
        }
        return titles.isEmpty() ? "via " + vuln.path("via") : String.join("; ", titles);
    }

    private static String npmSeverity(String severity) {
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> SecurityFinding.CRITICAL;
            case "high" -> SecurityFinding.HIGH;
            case "low" -> SecurityFinding.LOW;
            case "info" -> SecurityFinding.INFO;
            default -> SecurityFinding.MEDIUM;
        };
    }

    // ---------------------------------------------------------------- Maven

    private String maven(List<SecurityFinding> findings) throws IOException {
        Files.createDirectories(workDir);
        Path out = workDir.resolve("maven-dependencies.txt");
        Files.deleteIfExists(out);
        ProcResult result = shell.run("maven-dependencies",
                "mvn -q -B dependency:list -DoutputFile=\"" + out + "\" -DappendOutput=true");
        if (Shell.missing(result)) {
            return "Maven: mvn not installed";
        }
        if (!Files.isRegularFile(out)) {
            return "Maven: dependency:list failed (exit " + result.exitCode() + ")";
        }
        Set<OsvQuery> queries = new LinkedHashSet<>();
        for (String line : Files.readAllLines(out)) {
            Matcher m = MAVEN_LINE.matcher(line);
            if (m.find() && !"test".equals(m.group(4))) {
                queries.add(new OsvQuery("Maven", m.group(1) + ":" + m.group(2), m.group(3)));
            }
        }
        return "Maven: " + lookUp(new ArrayList<>(queries), findings) + " of "
                + queries.size() + " shipped dependencies vulnerable";
    }

    // --------------------------------------------------------------- Python

    private List<Path> requirementFiles() throws IOException {
        try (var files = Files.list(repo)) {
            return files.filter(p -> p.getFileName().toString().matches("requirements.*\\.txt"))
                    .sorted().toList();
        }
    }

    private String python(List<Path> files, List<SecurityFinding> findings) throws IOException {
        Set<OsvQuery> queries = new LinkedHashSet<>();
        int unpinned = 0;
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                    continue;
                }
                Matcher m = PINNED.matcher(trimmed);
                if (m.matches()) {
                    queries.add(new OsvQuery("PyPI", m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
                } else {
                    unpinned++;
                }
            }
        }
        String note = "Python: " + lookUp(new ArrayList<>(queries), findings) + " of "
                + queries.size() + " pinned requirement(s) vulnerable";
        return unpinned == 0 ? note
                : note + ", " + unpinned + " unpinned and not checked (no exact version to look up)";
    }

    // ------------------------------------------------------------------ OSV

    private int lookUp(List<OsvQuery> queries, List<SecurityFinding> findings) throws IOException {
        if (queries.isEmpty()) {
            return 0;
        }
        List<List<OsvVuln>> results = osv.lookup(queries);
        int vulnerable = 0;
        for (int i = 0; i < queries.size(); i++) {
            List<OsvVuln> vulns = i < results.size() ? results.get(i) : List.of();
            if (!vulns.isEmpty()) {
                vulnerable++;
            }
            OsvQuery query = queries.get(i);
            for (OsvVuln vuln : vulns) {
                boolean fixable = !vuln.fixedIn().isBlank();
                findings.add(new SecurityFinding(LAYER, vuln.severity(),
                        query.name() + " " + query.version() + ": " + vuln.id(),
                        query.ecosystem() + ": " + query.name() + " " + query.version(),
                        (vuln.summary().isBlank() ? vuln.id() : vuln.summary())
                                + (fixable ? "; fixed in " + vuln.fixedIn() : "; no fixed version yet"),
                        fixable && isSevere(vuln.severity())));
            }
        }
        return vulnerable;
    }

    private static boolean isSevere(String severity) {
        return SecurityFinding.CRITICAL.equals(severity) || SecurityFinding.HIGH.equals(severity);
    }
}
