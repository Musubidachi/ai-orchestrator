package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dashboard's extras (see {@code ui/CLAUDE.md}): each one is attached, served, parses, and keeps
 * its names to itself.
 *
 * <p>The last is the one that matters most. An extra is loaded before the page's own script, and
 * one that declared a top-level name the page also declares would break not itself but the page:
 * the page's script would stop at the redeclaration, and the dashboard would show nothing at all.
 * {@code node --check} cannot see that, because each file parses on its own, so the wrapping is
 * checked directly.
 */
class ExtrasTest {

    private static final Pattern TAG = Pattern.compile("<script src=\"/extras/([^\"]+)\"></script>");

    @TempDir
    Path root;

    private String base;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void startServer() throws Exception {
        Path wishlist = Files.createDirectories(root.resolve("wishlist"));
        Path runs = Files.createDirectories(root.resolve("runs"));
        // Port 0 so the test never collides with a running editor.
        base = new UiServer(wishlist, runs, 0, null, null, null).start();
    }

    @Test
    @DisplayName("every extra the dashboard attaches is served as a script")
    void attachedExtrasAreServed() throws Exception {
        List<String> attached = attached();
        assertFalse(attached.isEmpty(), "the dashboard attaches no extras");

        for (String name : attached) {
            HttpResponse<String> response = get("/extras/" + name);
            assertEquals(200, response.statusCode(), name + " is attached but not served");
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"),
                    name + " is not served as a script, so the browser would refuse to run it");
            assertEquals(Files.readString(extrasDir().resolve(name)), response.body());
        }
    }

    @Test
    @DisplayName("nothing but an extra, by its plain name, is served from /extras/")
    void onlyExtrasAreServed() throws Exception {
        // Status.js names a real file on a disk that ignores case, which is why names are checked
        // before anything is looked up.
        for (String path : List.of("/extras/nothing.js", "/extras/", "/extras/Status.js",
                "/extras/%2e%2e%2findex.html", "/extras/..%2F..%2Fui%2Findex.html")) {
            assertEquals(404, get(path).statusCode(), path + " should not be served");
        }
    }

    @Test
    @DisplayName("every extra is attached, parses, and keeps its names to itself")
    void everyExtraIsAttachedAndWrapped() throws Exception {
        List<Path> extras = extras();
        assertFalse(extras.isEmpty(), "ui/extras has no extras in it");
        List<String> attached = attached();

        for (Path extra : extras) {
            String name = extra.getFileName().toString();
            assertTrue(attached.contains(name), name + " is in ui/extras, but nothing attaches it");
            String code = afterOpeningComments(Files.readString(extra));
            assertTrue(code.startsWith("(() => {") && code.endsWith("})();"),
                    name + " must wrap itself in (() => { ... })(); so its names cannot collide with the page's");
            parses(extra);
        }
    }

    private void parses(Path extra) throws Exception {
        assumeTrue(nodeAvailable(), "node is not on PATH, so the extras cannot be parsed here");

        Process check = new ProcessBuilder("node", "--check", extra.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS), "node --check did not finish");

        assertEquals(0, check.exitValue(), extra.getFileName() + " does not parse:\n" + output);
    }

    /** The code itself: what is left once the comments that open the file are taken off. */
    private static String afterOpeningComments(String file) {
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

    /** The extras the dashboard attaches, by the names its tags give. */
    private List<String> attached() throws IOException {
        String page;
        try (InputStream in = getClass().getResourceAsStream("/ui/index.html")) {
            assertTrue(in != null, "the editor page is missing from the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> names = new ArrayList<>();
        Matcher tags = TAG.matcher(page);
        while (tags.find()) {
            names.add(tags.group(1));
        }
        return names;
    }

    private List<Path> extras() throws Exception {
        try (Stream<Path> files = Files.list(extrasDir())) {
            return files.filter(file -> file.getFileName().toString().endsWith(".js")).sorted().toList();
        }
    }

    private Path extrasDir() throws Exception {
        return Path.of(getClass().getResource("/ui/extras").toURI());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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
