package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a web page open in some other tab can make the editor do anything.
 *
 * <p>It could. Checked on this machine against a throwaway root: a POST carrying
 * {@code Origin: https://evil.example} and {@code Content-Type: text/plain} — the shape any page can
 * send without the browser asking permission first — wrote a spec with {@code acceptance: calc.exe}
 * into the wishlist. The gate executes acceptance commands and the agents run with permission to do
 * anything, so that was a drive-by path to running code on the machine.
 *
 * <p>Raw sockets rather than {@code HttpClient}, because the client will not let a test choose the
 * {@code Host} header, and a forged Host is exactly what DNS rebinding produces.
 */
class CrossSiteTest {

    @TempDir
    Path root;

    private Path wishlist;
    private int port;

    private static final String PLANTED = """
            {"front":{"id":"666","title":"Planted","priority":9,"acceptance":["calc.exe"]},
             "body":"## Goal\\nplanted by a web page\\n","originalFile":""}""";

    @BeforeEach
    void start() throws IOException {
        wishlist = Files.createDirectories(root.resolve("wishlist"));
        Files.createDirectories(root.resolve("runs"));
        String base = new UiServer(wishlist, root.resolve("runs"), 0, null, null, null).start();
        port = URI.create(base).getPort();
    }

    /** Status code and body of one hand-written request. */
    private record Reply(int status, String body, String headers) {
    }

    private Reply send(String method, String path, String host, String origin, String fetchSite,
                       String contentType, String body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        if (fetchSite != null) {
            request.append("Sec-Fetch-Site: ").append(fetchSite).append("\r\n");
        }
        if (contentType != null) {
            request.append("Content-Type: ").append(contentType).append("\r\n");
        }
        request.append("Content-Length: ").append(payload.length).append("\r\n\r\n");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(response.substring(9, 12));
            int split = response.indexOf("\r\n\r\n");
            return new Reply(status, response.substring(split + 4), response.substring(0, split));
        }
    }

    private String local() {
        return "localhost:" + port;
    }

    private boolean planted() throws IOException {
        try (Stream<Path> files = Files.list(wishlist)) {
            return files.anyMatch(file -> file.getFileName().toString().startsWith("666"));
        }
    }

    @Test
    @DisplayName("the measured attack: a page on another site cannot write a spec")
    void crossSiteSaveIsRefused() throws IOException {
        Reply reply = send("POST", "/api/save", local(), "https://evil.example", "cross-site",
                "text/plain", PLANTED);

        assertEquals(403, reply.status(), reply.body());
        assertFalse(planted(), "a web page wrote an executable spec into the wishlist");
    }

    @Test
    @DisplayName("even with no Origin, a non-JSON write is refused")
    void textPlainWritesAreRefused() throws IOException {
        // A browser that omitted Origin would still be unable to send JSON across sites without a
        // preflight this server never answers. Requiring JSON is what makes that true.
        Reply reply = send("POST", "/api/save", local(), null, null, "text/plain", PLANTED);

        assertEquals(403, reply.status(), reply.body());
        assertFalse(planted());
    }

    @Test
    @DisplayName("a GET cannot change anything, so an image tag cannot stop Forge")
    void getOnAWriteIsRefused() throws IOException {
        Reply reply = send("GET", "/api/quit", local(), null, null, null, null);

        assertEquals(403, reply.status(), reply.body());
    }

    @Test
    @DisplayName("a hostile domain resolving to 127.0.0.1 is not answered at all")
    void dnsRebindingIsRefused() throws IOException {
        // Rebinding makes the attacker's page same-origin with the editor as far as the browser is
        // concerned, so an Origin check alone would pass it — and it could read the responses too.
        Reply read = send("GET", "/api/list", "rebind.evil.example:" + port, null, null, null, null);

        assertEquals(403, read.status(), read.body());
    }

    @Test
    @DisplayName("the editor's own writes still work")
    void sameOriginJsonWritesSucceed() throws IOException {
        Reply reply = send("POST", "/api/save", local(), "http://" + local(), "same-origin",
                "application/json", PLANTED);

        assertEquals(200, reply.status(), reply.body());
        assertTrue(planted(), "the editor could not save its own spec");
    }

    @Test
    @DisplayName("a local script with no Origin, like quiesce.ps1, can still write")
    void localClientsWithoutOriginSucceed() throws IOException {
        Reply reply = send("POST", "/api/save", "127.0.0.1:" + port, null, null,
                "application/json", PLANTED);

        assertEquals(200, reply.status(), reply.body());
    }

    // ------------------------------------------------------------- same origin, not trusted

    private Path runFile(String relative, String content) throws IOException {
        Path file = root.resolve("runs").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("a page an agent wrote is served sandboxed, so its scripts cannot use the API")
    void agentWrittenPagesAreSandboxed() throws IOException {
        // The guard trusts this origin, and /runs/ serves from it. Project 006's evidence is an
        // HTML file the agent wrote; a prompt-injected agent could put a fetch to /api/jobs/start
        // in one, and it would arrive with this server's own Origin and pass every check.
        runFile("006/evidence/session-3/out.html",
                "<script>fetch('/api/jobs/start',{method:'POST'})</script>");

        Reply reply = send("GET", "/runs/006/evidence/session-3/out.html", local(), null, null,
                null, null);

        assertEquals(200, reply.status());
        assertTrue(reply.headers().toLowerCase().contains("content-security-policy: sandbox"),
                "agent-written HTML is served with this origin's authority:\n" + reply.headers());
        assertFalse(reply.headers().contains("allow-same-origin"),
                "allow-same-origin would hand the origin straight back");
    }

    @Test
    @DisplayName("the report Forge writes is not sandboxed, because its form has to post")
    void theReportKeepsItsOrigin() throws IOException {
        runFile("006/report.html", "<h1>report</h1>");

        Reply reply = send("GET", "/runs/006/report.html", local(), null, null, null, null);

        assertEquals(200, reply.status());
        // Header names are case-insensitive, and the JDK server writes this one as
        // "Content-security-policy".
        assertFalse(reply.headers().toLowerCase().contains("content-security-policy"), reply.headers());
    }

    @Test
    @DisplayName("a report-named file inside a repository is still the agent's, and sandboxed")
    void nestedReportsAreNotExempt() throws IOException {
        runFile("006/repo/report.html", "<script>alert(1)</script>");

        Reply reply = send("GET", "/runs/006/repo/report.html", local(), null, null, null, null);

        assertTrue(reply.headers().toLowerCase().contains("content-security-policy: sandbox"), reply.headers());
    }

    @Test
    @DisplayName("a request from a sandboxed page cannot write")
    void opaqueOriginsAreRefused() throws IOException {
        Reply reply = send("POST", "/api/save", local(), "null", "cross-site",
                "application/json", PLANTED);

        assertEquals(403, reply.status(), reply.body());
        assertFalse(planted());
    }

    @Test
    @DisplayName("a report can still be opened from a link on another page")
    void crossSiteReadsAreAnswered() throws IOException {
        // A read is unreadable to the site that asked, so refusing it protects nothing and would
        // break a report linked from anywhere else.
        Reply reply = send("GET", "/api/list", local(), null, "cross-site", null, null);

        assertEquals(200, reply.status(), reply.body());
    }
}
