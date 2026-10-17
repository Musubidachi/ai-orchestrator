package dev.fmangoni.forge.security.layer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.security.layer.model.PatternRule;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Risky code patterns: semgrep when it is installed, a short built-in list otherwise.
 *
 * <p>Everything this raises is a note, never serious. A pattern match cannot tell an
 * {@code innerHTML} assignment of a constant from one of user input, and a repair turn spent on a
 * false positive teaches the agent to write around the scanner rather than to write safer code. The
 * AI security review is given these notes and is the layer that decides whether one is real.
 */
public final class StaticAnalysis {

    public static final String LAYER = "static analysis";

    private static final Set<String> WEB = Set.of("js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "vue", "svelte");

    private static final List<PatternRule> RULES = List.of(
            new PatternRule(WEB, Pattern.compile("\\beval\\s*\\("), "eval() on a string", SecurityFinding.MEDIUM),
            new PatternRule(WEB, Pattern.compile("\\bnew\\s+Function\\s*\\("), "code built from a string with new Function",
                    SecurityFinding.MEDIUM),
            new PatternRule(WEB, Pattern.compile("\\.(?:innerHTML|outerHTML)\\s*\\+?=\\s*+(?![\"'][^\"'`]*[\"']\\s*;?\\s*$)"),
                    "HTML assigned from a non-constant value (possible XSS)", SecurityFinding.MEDIUM),
            new PatternRule(WEB, Pattern.compile("\\bdocument\\.write\\s*\\("), "document.write", SecurityFinding.LOW),
            new PatternRule(WEB, Pattern.compile("\\bexec(?:Sync)?\\s*\\(\\s*`[^`]*\\$\\{"),
                    "shell command built with a template string (possible command injection)", SecurityFinding.MEDIUM),
            new PatternRule(Set.of("java", "kt"), Pattern.compile("\\.exec\\s*\\([^)]*\\+"),
                    "process started from a concatenated string (possible command injection)", SecurityFinding.MEDIUM),
            new PatternRule(Set.of("java", "kt", "py", "js", "ts"),
                    Pattern.compile("(?i)\"\\s*(?:select|insert|update|delete)\\s[^\"]*\"\\s*\\+"),
                    "SQL built by concatenation (possible SQL injection)", SecurityFinding.MEDIUM),
            new PatternRule(Set.of("java", "kt"), Pattern.compile("new\\s+ObjectInputStream\\s*\\("),
                    "Java deserialization", SecurityFinding.LOW),
            new PatternRule(Set.of("java", "kt"), Pattern.compile("setHostnameVerifier\\s*\\([^)]*->\\s*true|TrustAll"),
                    "TLS certificate or hostname checks disabled", SecurityFinding.MEDIUM),
            new PatternRule(Set.of("py"), Pattern.compile("\\bpickle\\.loads?\\s*\\("), "pickle deserialization",
                    SecurityFinding.MEDIUM),
            new PatternRule(Set.of("py"), Pattern.compile("\\byaml\\.load\\s*\\((?![^)]*SafeLoader)"),
                    "yaml.load without SafeLoader", SecurityFinding.MEDIUM),
            new PatternRule(Set.of("py"), Pattern.compile("shell\\s*=\\s*True"), "subprocess with shell=True",
                    SecurityFinding.MEDIUM),
            new PatternRule(Set.of("py"), Pattern.compile("verify\\s*=\\s*False"), "TLS verification disabled",
                    SecurityFinding.MEDIUM));

    /** Test code is allowed to do unsafe things to prove the real code refuses them. */
    private static final Pattern TEST_PATH = Pattern.compile(
            "(^|/)(test|tests|__tests__|e2e|spec)/|\\.(test|spec)\\.[a-z]+$|Test\\.java$");

    private final Path repo;
    private final Git git;
    private final Shell shell;
    private final Path workDir;

    public StaticAnalysis(Path repo, Git git, Shell shell, Path workDir) {
        this.repo = repo;
        this.git = git;
        this.shell = shell;
        this.workDir = workDir;
    }

    public LayerResult run() {
        LayerResult semgrep = semgrep();
        if (semgrep != null) {
            return semgrep;
        }
        List<SecurityFinding> findings = new ArrayList<>();
        int scanned = 0;
        for (String file : git.files()) {
            String extension = extensionOf(file);
            if (TEST_PATH.matcher(file).find() || RULES.stream().noneMatch(r -> r.extensions().contains(extension))) {
                continue;
            }
            String text = read(repo.resolve(file));
            if (text == null) {
                continue;
            }
            scanned++;
            String[] lines = text.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                for (PatternRule rule : RULES) {
                    if (rule.extensions().contains(extension) && rule.pattern().matcher(lines[i]).find()) {
                        findings.add(new SecurityFinding(LAYER, rule.severity(), rule.title(),
                                file + ":" + (i + 1), lines[i].strip(), false));
                    }
                }
            }
        }
        return LayerResult.ran(LAYER, "built-in rules over " + scanned
                + " source file(s); semgrep is not installed (pip install semgrep) for a fuller check",
                findings);
    }

    /** Semgrep's findings, or null when it is not installed and the built-in rules should run. */
    private LayerResult semgrep() {
        try {
            Files.createDirectories(workDir);
            Path out = workDir.resolve("semgrep.json");
            Files.deleteIfExists(out);
            ProcResult result = shell.run("semgrep", "semgrep scan --config p/security-audit --metrics off "
                    + "--json --quiet --output \"" + out + "\"");
            if (Shell.missing(result) || !Files.isRegularFile(out)) {
                return null;
            }
            List<SecurityFinding> findings = new ArrayList<>();
            for (JsonNode hit : new ObjectMapper().readTree(Files.readString(out)).path("results")) {
                String severity = switch (hit.path("extra").path("severity").asText("").toUpperCase(Locale.ROOT)) {
                    case "ERROR" -> SecurityFinding.HIGH;
                    case "WARNING" -> SecurityFinding.MEDIUM;
                    default -> SecurityFinding.LOW;
                };
                findings.add(new SecurityFinding(LAYER, severity, hit.path("extra").path("message").asText(),
                        hit.path("path").asText() + ":" + hit.path("start").path("line").asInt(),
                        hit.path("check_id").asText(), false));
            }
            return LayerResult.ran(LAYER, "semgrep p/security-audit", findings);
        } catch (IOException e) {
            return null;
        }
    }

    private static String extensionOf(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String read(Path file) {
        try {
            return Files.size(file) > 1024 * 1024 ? null : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }
}
