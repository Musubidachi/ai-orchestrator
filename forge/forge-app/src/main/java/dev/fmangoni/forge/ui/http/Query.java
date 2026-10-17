package dev.fmangoni.forge.ui.http;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** Reads parameters from a request's query string. */
@UtilityClass
public class Query {

    /** The parameter's value; a request without it is refused as a bad request. */
    public String required(HttpExchange exchange, String key) {
        return optional(exchange, key).orElseThrow(() -> new IllegalArgumentException("missing ?" + key));
    }

    /**
     * The parameter's value, or empty when the request did not send it.
     *
     * <p>Separate from {@link #required} so a parameter with a default can have one. {@code /api/quit}
     * documented a default wait for years, but read it with the required form, so a stop request
     * without {@code ?wait=} was refused as a bad request instead of waiting ten minutes.
     */
    public Optional<String> optional(HttpExchange exchange, String key) {
        return optional(exchange.getRequestURI().getRawQuery(), key);
    }

    Optional<String> optional(String rawQuery, String key) {
        if (rawQuery == null) {
            return Optional.empty();
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return Optional.of(URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}
