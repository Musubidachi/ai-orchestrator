package dev.fmangoni.forge.ui.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.util.Optional;

/**
 * Registers endpoints on the server, each as a read or a write, and there is no third way.
 *
 * <p>A write is anything that changes a file, starts or stops a run, or spends a vendor's quota; see
 * {@link RequestGuard} for what that classification buys. Every handler is also wrapped so that a
 * failure becomes a JSON error instead of an empty response: without it a thrown exception closes
 * the connection silently, and the editor shows nothing at all — which reads to the user as "the
 * save worked".
 */
public final class Endpoints {

    private final HttpServer server;
    private final RequestGuard requests;

    public Endpoints(HttpServer server, RequestGuard requests) {
        this.server = server;
        this.requests = requests;
    }

    public void read(String path, HttpHandler handler) {
        server.createContext(path, guarded(handler, false));
    }

    public void write(String path, HttpHandler handler) {
        server.createContext(path, guarded(handler, true));
    }

    /** For the one endpoint that reads on GET and writes on POST: the method decides which it is. */
    public void readOrWrite(String path, HttpHandler handler) {
        server.createContext(path, guarded(handler, null));
    }

    private HttpHandler guarded(HttpHandler handler, Boolean writes) {
        return caught(exchange -> {
            Headers headers = exchange.getRequestHeaders();
            boolean changes = writes != null ? writes : !"GET".equalsIgnoreCase(exchange.getRequestMethod());
            Optional<String> refused = requests.refuse(
                    exchange.getRequestMethod(),
                    headers.getFirst("Host"),
                    headers.getFirst("Origin"),
                    headers.getFirst("Sec-Fetch-Site"),
                    headers.getFirst("Content-Type"),
                    changes);
            if (refused.isPresent()) {
                Http.error(exchange, 403, refused.get());
                return;
            }
            handler.handle(exchange);
        });
    }

    private static HttpHandler caught(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (IllegalArgumentException e) {
                Http.error(exchange, 400, e.getMessage());
            } catch (Exception e) {
                Http.error(exchange, 500, String.valueOf(e.getMessage()));
            } finally {
                exchange.close();
            }
        };
    }
}
