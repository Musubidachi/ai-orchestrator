package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Serves the dashboard's extras: scripts in {@code ui/extras/}, each adding one thing to the page.
 *
 * <p>An extra is attached in one place, its tag in the block beside {@code attach.js}, and hears what
 * the page loaded from the two events the page announces, {@code forge:jobs} and {@code forge:quota}.
 * Removing one is deleting its file and its tag; nothing else in the page knows it is there.
 *
 * <p>Only a plain file name is served, so nothing can walk out of the folder: anything else is a
 * 404, and so is a name the jar does not have.
 */
public final class ExtraRoutes {

    private static final String PREFIX = "/extras/";

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+\\.js");

    public void register(Endpoints endpoints) {
        endpoints.read(PREFIX, this::serve);
    }

    private void serve(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring(PREFIX.length());
        String resource = "/ui/extras/" + name;
        if (!NAME.matcher(name).matches() || ExtraRoutes.class.getResource(resource) == null) {
            Http.error(exchange, 404, "no extra called " + name);
            return;
        }
        Http.bundled(exchange, resource);
    }
}
