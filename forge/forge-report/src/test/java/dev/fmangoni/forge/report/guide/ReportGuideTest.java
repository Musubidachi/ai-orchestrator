package dev.fmangoni.forge.report.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.ProjectReport;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guide's script, and the one place it is attached.
 *
 * <p>What it shows is tested where a page can be looked at: {@code ui-tests/tests/report-guide.spec.js},
 * against the reports the Playwright fixture generates.
 */
class ReportGuideTest {

    private static final String TAG = "<script id=\"report-guide\">";

    @TempDir
    Path root;

    @Test
    @DisplayName("the script parses")
    void parses() throws Exception {
        assumeTrue(nodeAvailable(), "node is not on PATH, so the guide cannot be parsed here");
        Path file = root.resolve("guide.js");
        Files.writeString(file, script(), StandardCharsets.UTF_8);

        Process check = new ProcessBuilder("node", "--check", file.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), "node --check did not finish");

        assertEquals(0, check.exitValue(), "guide.js does not parse:\n" + output);
    }

    @Test
    @DisplayName("it is wrapped, so its names cannot meet the review form's")
    void wrapped() throws IOException {
        String code = afterOpeningComment(script());
        assertTrue(code.startsWith("(() => {") && code.endsWith("})();"),
                "guide.js must wrap itself in (() => { ... })(); the review form's script shares the page");
    }

    @Test
    @DisplayName("nothing in it ends its script element early")
    void staysInsideItsElement() throws IOException {
        // It is inline: the first "</script" in it would close the element there, and the rest of
        // the guide would be printed as text at the foot of the report.
        assertFalse(script().toLowerCase(Locale.ROOT).contains("</script"));
    }

    @Test
    @DisplayName("a project's page carries it once, after its last section")
    void attachedOnce() throws IOException {
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.evidence());
        RunState state = new RunState();
        state.setId("001");
        state.setTitle("A thing");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);

        new ProjectReport(paths, state, null).write();
        String page = Files.readString(paths.reportFile(), StandardCharsets.UTF_8);

        int at = page.indexOf(TAG);
        assertTrue(at >= 0, "the page has no guide:\n" + page);
        assertEquals(at, page.lastIndexOf(TAG), "the guide is attached more than once");
        assertTrue(at > page.indexOf("<h2>Where to look</h2>"),
                "the guide would run before the page's last section is there to read");
        assertTrue(at < page.indexOf("</main>"), "the guide is outside the page it reads");
        assertTrue(page.contains(TAG + "\n" + script() + "</script>"), "the page does not carry the script as it is");
    }

    private String script() throws IOException {
        try (InputStream in = ReportGuide.class.getResourceAsStream("guide.js")) {
            assertTrue(in != null, "guide.js is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The code itself: what is left once the comments that open the file are taken off. */
    private static String afterOpeningComment(String file) {
        String rest = file.strip();
        while (rest.startsWith("/*") || rest.startsWith("//")) {
            String close = rest.startsWith("/*") ? "*/" : "\n";
            int end = rest.indexOf(close);
            if (end < 0) {
                return "";
            }
            rest = rest.substring(end + close.length()).strip();
        }
        return rest;
    }

    private boolean nodeAvailable() {
        try {
            Process probe = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
