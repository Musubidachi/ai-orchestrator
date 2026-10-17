package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the dashboard's JavaScript, which is the cheapest test here and nearly the most valuable.
 *
 * <p>A syntax error anywhere in that script stops the whole page rendering — not one broken
 * control, but a dashboard with no cards at all. It has happened: a stray newline inside a string
 * literal shipped and every Java test still passed, because nothing here had ever read the script.
 *
 * <p>Uses {@code node --check}, which parses without executing. Node is not required to build
 * Forge, so the test skips when it is absent rather than failing on a machine that never needed it.
 */
class UiScriptTest {

    private static final Pattern SCRIPT =
            Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);

    @TempDir
    Path work;

    private String page() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ui/index.html")) {
            assertTrue(in != null, "the editor page is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the dashboard script parses")
    void scriptParses() throws Exception {
        Matcher matcher = SCRIPT.matcher(page());
        assertTrue(matcher.find(), "no <script> block found in the editor page");

        Path script = work.resolve("ui.mjs");
        Files.writeString(script, matcher.group(1), StandardCharsets.UTF_8);

        assumeTrue(nodeAvailable(), "node is not on PATH, so the script cannot be parsed here");

        Process check = new ProcessBuilder("node", "--check", script.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), "node --check did not finish");

        assertEquals(0, check.exitValue(), "the dashboard script does not parse:\n" + output);
    }

    @Test
    @DisplayName("the quick review script parses")
    void quickReviewScriptParses() throws Exception {
        parses("review");
    }

    @Test
    @DisplayName("the polish page's script parses")
    void polishScriptParses() throws Exception {
        parses("polish");
    }

    /** The pages that are not the dashboard: each is one file with one script, checked the same way. */
    private void parses(String name) throws Exception {
        String page;
        try (InputStream in = getClass().getResourceAsStream("/ui/" + name + ".html")) {
            assertTrue(in != null, "the " + name + " page is missing from the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = SCRIPT.matcher(page);
        assertTrue(matcher.find(), "no <script> block found in the " + name + " page");

        Path script = work.resolve(name + ".mjs");
        Files.writeString(script, matcher.group(1), StandardCharsets.UTF_8);

        assumeTrue(nodeAvailable(), "node is not on PATH, so the script cannot be parsed here");

        Process check = new ProcessBuilder("node", "--check", script.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), "node --check did not finish");

        assertEquals(0, check.exitValue(), "the " + name + " script does not parse:\n" + output);
    }

    @Test
    @DisplayName("every handler the page calls is actually defined")
    void inlineHandlersExist() throws Exception {
        String page = page();

        // onclick="foo()" in the markup is resolved at click time, so a renamed function fails
        // silently until someone presses the button.
        Matcher handlers = Pattern.compile("on(?:click|change)=\"(\\w+)\\(").matcher(page);
        while (handlers.find()) {
            String name = handlers.group(1);
            assertTrue(page.contains("function " + name + "(")
                            || page.contains("async function " + name + "("),
                    "markup calls " + name + "() but nothing defines it");
        }
    }

    @Test
    @DisplayName("the ids the script reaches for exist in the markup")
    void referencedIdsExist() throws Exception {
        String page = page();
        List<String> required = List.of(
                "jobs", "jobsSummary", "jobsNote", "quotaBar", "autoRun",
                "jobsView", "wishlistView", "navJobs", "navWishlist",
                "id", "title", "priority", "parent", "assign", "stack",
                "rounds", "maxSessions", "agentTimeout", "gateTimeout",
                "acceptance", "evidence", "body", "status", "compactAfter",
                "goal", "nonGoals", "notes",
                "claudeModel", "codexModel", "antigravityModel",
                "idea", "suggestAgent", "suggestBtn");

        for (String id : required) {
            assertTrue(page.contains("id=\"" + id + "\""),
                    "the script uses $(\"" + id + "\") but the markup has no such element");
        }
    }

    @Test
    @DisplayName("no top-level function shadows a browser API the page relies on")
    void nothingShadowsWindow() throws Exception {
        String page = page();

        // A top-level `function open(...)` becomes window.open, replacing the browser's own. That
        // shipped: Review & Decide called window.open and reached the page's spec loader instead,
        // so the button did nothing and left only a 400 for /api/open?file=undefined in a console
        // nobody had open. Nothing in the language warns about it and node --check is happy.
        //
        // Deliberately a short list of names this page might plausibly choose, not every member of
        // window. A guard that fails on `status` or `name` would be turned off within a week.
        List<String> reserved = List.of(
                "open", "close", "print", "alert", "confirm", "prompt", "focus", "blur",
                "scroll", "stop", "find", "fetch", "postMessage");

        for (String name : reserved) {
            for (String declaration : List.of("function " + name + "(",
                    "async function " + name + "(")) {
                assertTrue(!page.contains(declaration),
                        declaration + ") declares window." + name
                                + ", replacing the browser's own — rename it");
            }
        }
    }

    private boolean nodeAvailable() {
        try {
            Process probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
            return probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
