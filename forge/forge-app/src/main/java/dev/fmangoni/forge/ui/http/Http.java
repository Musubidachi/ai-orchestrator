package dev.fmangoni.forge.ui.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Request and response plumbing for the editor's small JSON API. */
@UtilityClass
public class Http {

    /**
     * The one mapper the whole API serialises through.
     *
     * <p>{@code JavaTimeModule} is not optional here. Without it Jackson refuses to write an
     * {@link Instant} at all, and because that failure happens midway through the
     * response the endpoint still answers 200 with a truncated body — so the browser sees a
     * successful request and renders nothing. That is exactly how the quota banner went missing
     * while a vendor really was rate-limited.
     */
    private final ObjectMapper JSON = Json.mapper();

    public ObjectMapper mapper() {
        return JSON;
    }

    public String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public <T> T bodyAs(HttpExchange exchange, Class<T> type) throws IOException {
        return JSON.readValue(body(exchange), type);
    }

    public void json(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                JSON.writeValueAsBytes(payload));
    }

    public void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message));
    }

    public void html(HttpExchange exchange, String content) throws IOException {
        send(exchange, 200, "text/html; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    /** A page or script shipped inside the jar, such as {@code /ui/index.html}. */
    public void bundled(HttpExchange exchange, String resource) throws IOException {
        try (InputStream in = Http.class.getResourceAsStream(resource)) {
            if (in == null) {
                error(exchange, 500, resource + " is missing from the jar");
                return;
            }
            String type = resource.endsWith(".js") ? "text/javascript" : "text/html";
            send(exchange, 200, type + "; charset=utf-8", in.readAllBytes());
        }
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] payload)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // The editor writes to the filesystem, so nothing it serves should ever be cached.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
