package dev.fmangoni.forge.security.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.fmangoni.forge.process.local.LocalServer;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveProbeTest {

    @TempDir
    Path root;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(boolean careless) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (careless && path.equals("/.git/HEAD")) {
                send(exchange, 200, "text/plain", "ref: refs/heads/main\n");
            } else if (path.equals("/")) {
                String q = query == null ? "" : URLDecoder.decode(query.replaceAll(".*?q=([^&]*).*", "$1"),
                        StandardCharsets.UTF_8);
                if (!careless) {
                    q = q.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
                    exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
                    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                }
                send(exchange, 200, "text/html", "<html><body>Results for " + q + "</body></html>");
            } else {
                if (careless) {
                    send(exchange, 404, "text/html",
                            "<pre>java.lang.IllegalStateException: no route\n\tat com.example.Router(Router.java:42)</pre>");
                } else {
                    // What a single-page app does: every unknown path gets index.html. Its meta tags look
                    // like assignments, which is how a .env was once reported on a project without one.
                    send(exchange, 200, "text/html", """
                            <html>
                              <meta
                                name="viewport"
                                content="width=device-width, initial-scale=1.0"/>
                            MODE=arcade
                            </html>""");
                }
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("a careless app: exposed .git and confirmed reflection are serious, the rest are notes")
    void carelessApp() throws Exception {
        LayerResult result = new LiveProbe(serve(true), "/").run();

        List<String> serious = result.findings().stream().filter(SecurityFinding::serious)
                .map(SecurityFinding::title).sorted().toList();
        assertEquals(List.of("input is reflected into the page unescaped (confirmed XSS)",
                "the git repository is served"), serious, result.findings().toString());
        assertTrue(result.findings().stream().anyMatch(f -> f.title().equals("error pages show a stack trace")));
        assertTrue(result.findings().stream().anyMatch(f -> f.title().equals("security headers not set")));
    }

    @Test
    @DisplayName("a careful app: escaped output, headers set, dotfiles not served — nothing to report")
    void carefulApp() throws Exception {
        LayerResult result = new LiveProbe(serve(false), "/").run();

        assertEquals(LayerResult.RAN, result.status());
        assertEquals(List.of(), result.findings());
    }

    @Test
    @DisplayName("anything but this machine is refused before a request is sent")
    void onlyLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new LiveProbe(URI.create("http://example.com"), "/"));
        assertThrows(IllegalArgumentException.class, () -> new LiveProbe(URI.create("http://192.168.1.10:8080"), "/"));
    }

    @Test
    @DisplayName("the serve command is started on a port Forge chose, and stopped — tree and all — on close")
    void localServerLifecycle() throws Exception {
        Path app = root.resolve("Serve.java");
        Files.writeString(app, """
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                public class Serve {
                    public static void main(String[] args) throws Exception {
                        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
                        s.createContext("/", e -> { byte[] b = "hello".getBytes(); e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close(); });
                        s.start();
                    }
                }
                """);
        URI base;
        try (LocalServer local = LocalServer.start(root, "java \"" + app + "\" {port}", root.resolve("scripts"),
                Duration.ofSeconds(60))) {
            base = local.base();
            assertTrue(base.getHost().equals("127.0.0.1"), base.toString());
            assertEquals(LayerResult.RAN, new LiveProbe(base, "/").run().status());
        }
        Thread.sleep(500);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", base.getPort()), 500);
            throw new AssertionError("the server is still listening after close()");
        } catch (IOException expected) {
            // Refused: nothing is left holding the port.
        }
    }
}
