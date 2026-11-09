package dev.fmangoni.forge.report.look;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The look's script, the parts the report writes for it, and the one place it is attached.
 *
 * <p>What it looks like is tested where a page can be looked at: {@code ui-tests/tests/report-look.spec.js},
 * against the reports the Playwright fixture generates.
 */
class ReportLookTest {

    private static final String TEMPLATE = "<template id=\"report-look-parts\">";
    private static final String TAG = "<script id=\"report-look\">";

    @TempDir
    Path root;

    @Test
    @DisplayName("the script parses")
    void parses() throws Exception {
        assumeTrue(nodeAvailable(), "node is not on PATH, so the look cannot be parsed here");
        Path file = root.resolve("look.js");
        Files.writeString(file, script(), StandardCharsets.UTF_8);

        Process check = new ProcessBuilder("node", "--check", file.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), "node --check did not finish");

        assertEquals(0, check.exitValue(), "look.js does not parse:\n" + output);
    }

    @Test
    @DisplayName("it is wrapped, so its names cannot meet the guide's or the review form's")
    void wrapped() throws IOException {
        String code = afterOpeningComment(script());
        assertTrue(code.startsWith("(() => {") && code.endsWith("})();"),
                "look.js must wrap itself in (() => { ... })(); the guide and the review form share the page");
    }

    @Test
    @DisplayName("nothing in it ends its script element early")
    void staysInsideItsElement() throws IOException {
        assertFalse(script().toLowerCase(Locale.ROOT).contains("</script"));
    }

    @Test
    @DisplayName("the words on the page are the report's, never the script's")
    void saysNothingItself() throws IOException {
        // A phrase in the script would be on every page, where a test reading a page for what it
        // does not say would find it.
        for (String words : List.of("Go to your review", "Needs you", "Waiting for", "built by", "session",
                "usually", "slower than usual", "The tick")) {
            assertFalse(script().contains(words), "look.js says \"" + words + "\" itself");
        }
    }

    @Test
    @DisplayName("a project's page carries it once, after the guide it restyles, with its parts before it")
    void attachedOnce() throws IOException {
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.evidence());
        RunState state = state(Phase.DONE);

        new ProjectReport(paths, state, null).write();
        String page = Files.readString(paths.reportFile(), StandardCharsets.UTF_8);

        int at = page.indexOf(TAG);
        assertTrue(at >= 0, "the page has no look:\n" + page);
        assertEquals(at, page.lastIndexOf(TAG), "the look is attached more than once");
        assertEquals(page.indexOf(TEMPLATE), page.lastIndexOf(TEMPLATE), "its parts are written more than once");
        assertTrue(page.indexOf(TEMPLATE) < at, "the script would run before its parts are there");
        assertTrue(at > page.indexOf("<script id=\"report-guide\">"),
                "the look would run before the guide has built the rail and the bar it restyles");
        assertTrue(at < page.indexOf("</main>"), "the look is outside the page it restyles");
        assertTrue(page.contains(TAG + "\n" + script() + "</script>"), "the page does not carry the script as it is");
    }

    @Test
    @DisplayName("the header says the phase once, in the reader's words, and who built it in how many sessions")
    void header() {
        RunState state = state(Phase.DONE);
        state.setSessions(19);

        String parts = ReportLook.render(state);

        assertTrue(parts.contains("<span class=\"rl-chip DONE\">Waiting for your approval</span>"), parts);
        assertTrue(parts.contains("<span class=\"rl-meta\">001 · built by Claude · 19 sessions</span>"), parts);
    }

    @Test
    @DisplayName("a project held for a person needs you, whatever phase it stopped in, and says its repair rounds")
    void held() {
        RunState state = state(Phase.REVIEW_REPAIR);
        state.setNeedsPerson("still not fixed after 2 attempts");
        state.setSessions(1);
        state.setRepairRounds(2);

        String parts = ReportLook.render(state);

        assertTrue(parts.contains("<span class=\"rl-chip PARKED\">Needs you</span>"), parts);
        assertTrue(parts.contains("001 · built by Claude · 1 session · 2 repair rounds</span>"), parts);
    }

    @Test
    @DisplayName("a project suspended on its way into review waits for the cross-review, with a clock")
    void awaitingReview() {
        RunState state = state(Phase.SUSPENDED);
        state.setResumePhase(Phase.REVIEWING);

        String parts = ReportLook.render(state);

        assertTrue(parts.contains("<span class=\"rl-chip SUSPENDED\"><svg"), parts);
        assertTrue(parts.contains("</svg>Waiting for the cross-review</span>"), parts);
    }

    @Test
    @DisplayName("each command's time is a bar on one scale, with a tick at its usual time once there is one")
    void times() {
        RunState state = state(Phase.DONE);
        Map<String, List<Integer>> seconds = new LinkedHashMap<>();
        seconds.put("npm run build", List.of(1, 1));
        seconds.put("npm test", List.of(20, 21, 19, 60));
        seconds.put("npm run e2e", List.of(383));
        state.setCommandSeconds(seconds);

        String parts = ReportLook.render(state);

        // The longest is the scale. Measured once, it has no usual time to mark.
        assertTrue(parts.contains("<div class=\"rl-time\"><code>npm run e2e</code><span class=\"rl-track\">"
                + "<span class=\"rl-bar\" style=\"width:100.0%\"></span></span>"
                + "<span class=\"rl-took\"><b>6m 23s</b></span></div>"), parts);
        // 60s of 383, its usual 20s the middle of the three runs before it.
        assertTrue(parts.contains("<div class=\"rl-time rl-slow\"><code>npm test</code><span class=\"rl-track\">"
                + "<span class=\"rl-bar\" style=\"width:15.7%\"></span><span class=\"rl-tick\" style=\"left:5.2%\">"
                + "</span></span><span class=\"rl-took\"><b>1m</b> · usually 20s · <em>slower than usual</em>"
                + "</span></div>"), parts);
        assertTrue(parts.contains("<code>npm run build</code>"), parts);
        assertTrue(parts.contains("<p class=\"rl-foot\">"), parts);
    }

    @Test
    @DisplayName("a project with no times has no chart")
    void noTimes() {
        assertFalse(ReportLook.render(state(Phase.NEW)).contains("<div class=\"rl-times"));
    }

    private RunState state(Phase phase) {
        RunState state = new RunState();
        state.setId("001");
        state.setTitle("A thing");
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        return state;
    }

    private String script() throws IOException {
        try (InputStream in = ReportLook.class.getResourceAsStream("look.js")) {
            assertTrue(in != null, "look.js is missing from the classpath");
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
