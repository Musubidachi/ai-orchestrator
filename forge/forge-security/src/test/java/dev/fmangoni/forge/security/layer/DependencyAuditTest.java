package dev.fmangoni.forge.security.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.osv.OsvClient;
import dev.fmangoni.forge.security.osv.OsvHttpClient;
import dev.fmangoni.forge.security.osv.model.OsvQuery;
import dev.fmangoni.forge.security.osv.model.OsvVuln;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The audit against canned tool output and a fake OSV, so no network and no dependency tree. */
class DependencyAuditTest {

    @TempDir
    Path root;

    private final List<OsvQuery> asked = new ArrayList<>();

    private Path repo() throws IOException {
        return Files.createDirectories(root.resolve("repo"));
    }

    /** Writes {@code outputs.get(name)} to the file the command redirects or points at. */
    private Shell cannedShell(Map<String, String> outputs) {
        Pattern target = Pattern.compile("(?:> |-DoutputFile=)\"([^\"]+)\"");
        return (name, command) -> {
            Matcher m = target.matcher(command);
            try {
                if (m.find() && outputs.containsKey(name)) {
                    Files.writeString(Path.of(m.group(1)), outputs.get(name));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new ProcResult(1, "", "", false, Duration.ofSeconds(1));
        };
    }

    private OsvClient osv(Map<String, List<OsvVuln>> byName) {
        return queries -> {
            asked.addAll(queries);
            return queries.stream().map(q -> byName.getOrDefault(q.name(), List.of())).toList();
        };
    }

    private LayerResult audit(Path repo, Shell shell, OsvClient osv) {
        return new DependencyAudit(repo, root.resolve("security"), shell, osv).run();
    }

    @Test
    @DisplayName("npm: a high vulnerability with a fix is serious; a moderate one without is a note")
    void npmAudit() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("package.json"), "{}");
        Files.writeString(repo.resolve("package-lock.json"), "{}");
        String audit = """
                {"auditReportVersion":2,"vulnerabilities":{
                  "vite":{"name":"vite","severity":"high","isDirect":true,
                          "via":[{"title":"Arbitrary file read via dev server"}],
                          "range":"<5.4.6","fixAvailable":{"name":"vite","version":"5.4.6"}},
                  "nanoid":{"name":"nanoid","severity":"moderate","isDirect":false,
                            "via":["postcss"],"range":"<3.3.8","fixAvailable":false}}}
                """;

        String production = """
                {"auditReportVersion":2,"vulnerabilities":{
                  "vite":{"name":"vite","severity":"high"},"nanoid":{"name":"nanoid","severity":"moderate"}}}
                """;
        LayerResult result = audit(repo, cannedShell(Map.of("npm-audit", audit, "npm-audit-production", production)),
                osv(Map.of()));

        assertEquals(LayerResult.RAN, result.status(), result.detail());
        SecurityFinding vite = result.findings().stream()
                .filter(f -> f.title().startsWith("vite")).findFirst().orElseThrow();
        assertEquals(SecurityFinding.HIGH, vite.severity());
        assertTrue(vite.serious());
        assertTrue(vite.evidence().contains("Arbitrary file read"), vite.evidence());
        SecurityFinding nanoid = result.findings().stream()
                .filter(f -> f.title().startsWith("nanoid")).findFirst().orElseThrow();
        assertFalse(nanoid.serious(), "nothing to upgrade to, so a repair turn could not fix it");
    }

    @Test
    @DisplayName("npm: a vulnerability only in development tooling is reported, but is not serious")
    void devDependenciesAreNotSerious() throws Exception {
        // Invoice Tool, for real: six advisories, every one in vitest, playwright or the vite dev server.
        Path repo = repo();
        Files.writeString(repo.resolve("package.json"), "{}");
        Files.writeString(repo.resolve("package-lock.json"), "{}");
        String audit = """
                {"vulnerabilities":{"vitest":{"name":"vitest","severity":"critical","isDirect":true,
                  "via":[{"title":"Remote Code Execution when accessing a malicious website"}],"fixAvailable":true}}}
                """;
        String production = "{\"vulnerabilities\":{}}";

        LayerResult result = audit(repo, cannedShell(Map.of("npm-audit", audit, "npm-audit-production", production)),
                osv(Map.of()));

        SecurityFinding vitest = result.findings().get(0);
        assertEquals(SecurityFinding.CRITICAL, vitest.severity(), "still reported at its real severity");
        assertFalse(vitest.serious(), "but no repair turn for a tool that never reaches a user");
        assertTrue(vitest.title().endsWith("(development dependency)"), vitest.title());
    }

    @Test
    @DisplayName("Maven: shipped dependencies are looked up, test-scoped ones are not")
    void mavenLookup() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        String list = """

                The following files have been resolved:
                   org.apache.pdfbox:pdfbox:jar:2.0.1:compile -- module org.apache.pdfbox
                   com.fasterxml.jackson.core:jackson-databind:jar:2.9.8:runtime
                   org.junit.jupiter:junit-jupiter:jar:5.11.4:test
                """;
        OsvClient osv = osv(Map.of("com.fasterxml.jackson.core:jackson-databind", List.of(
                new OsvVuln("GHSA-xxxx", "Polymorphic typing deserialization",
                        SecurityFinding.CRITICAL, "2.9.9"))));

        LayerResult result = audit(repo, cannedShell(Map.of("maven-dependencies", list)), osv);

        assertEquals(List.of("org.apache.pdfbox:pdfbox", "com.fasterxml.jackson.core:jackson-databind"),
                asked.stream().map(OsvQuery::name).toList(), "junit is test scope and never ships");
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).serious());
        assertTrue(result.findings().get(0).evidence().contains("fixed in 2.9.9"));
        assertTrue(result.detail().contains("1 of 2"), result.detail());
    }

    @Test
    @DisplayName("Python: pinned requirements are looked up, and unpinned ones are counted as unchecked")
    void pythonRequirements() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("requirements.txt"), """
                # runtime
                Requests==2.19.0
                numpy>=1.20
                pillow[extra]==9.0.0 ; python_version > "3.8"
                -r other.txt
                """);

        LayerResult result = audit(repo, cannedShell(Map.of()), osv(Map.of()));

        assertEquals(List.of("requests", "pillow"), asked.stream().map(OsvQuery::name).toList());
        assertTrue(result.detail().contains("1 unpinned and not checked"), result.detail());
    }

    @Test
    @DisplayName("an unreachable database fails the layer loudly instead of reporting clean")
    void unreachableDatabase() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("requirements.txt"), "flask==0.12\n");

        LayerResult result = audit(repo, cannedShell(Map.of()), queries -> {
            throw new IOException("connection refused");
        });

        assertEquals(LayerResult.FAILED, result.status());
        assertTrue(result.detail().contains("connection refused"));
    }

    @Test
    @DisplayName("a project with nothing to audit says so rather than passing")
    void nothingToAudit() throws Exception {
        assertEquals(LayerResult.SKIPPED, audit(repo(), cannedShell(Map.of()), osv(Map.of())).status());
    }

    @Test
    @DisplayName("OSV's grading and first fixed version are read for the right package")
    void osvDetails() throws Exception {
        var vuln = new ObjectMapper().readTree("""
                {"id":"GHSA-1","database_specific":{"severity":"HIGH"},
                 "affected":[{"package":{"name":"other","ecosystem":"PyPI"},
                              "ranges":[{"events":[{"introduced":"0"},{"fixed":"9.9"}]}]},
                             {"package":{"name":"flask","ecosystem":"PyPI"},
                              "ranges":[{"events":[{"introduced":"0"},{"fixed":"1.0"}]}]}]}
                """);

        assertEquals(SecurityFinding.HIGH, OsvHttpClient.severityOf(vuln));
        assertEquals("1.0", OsvHttpClient.fixedIn(vuln, "flask"));
        assertEquals(SecurityFinding.MEDIUM, OsvHttpClient.severityOf(new ObjectMapper().readTree("{}")));
    }
}
