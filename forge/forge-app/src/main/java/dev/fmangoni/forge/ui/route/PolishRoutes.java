package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.jobs.polish.PolishView;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import dev.fmangoni.forge.ui.request.PolishRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Polish: its page, the board of designs it shows, and its two buttons.
 *
 * <p>Asking for designs and building one are both writes: each spends a vendor's quota. Reading the
 * board is not, and the page reads it every few seconds while the vendors are drawing — so it reads
 * one project, never the whole dashboard.
 */
public final class PolishRoutes {

    private static final String NO_CLI = "no agent CLI installed";

    /** Null when no CLI is installed, as for every other route that needs the projects. */
    private final JobRunner jobs;

    public PolishRoutes(JobRunner jobs) {
        this.jobs = jobs;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/polish", PolishRoutes::page);
        endpoints.read("/api/polish", this::board);
        endpoints.write("/api/polish/ask", this::ask);
        endpoints.write("/api/polish/build", this::build);
    }

    private void board(HttpExchange exchange) throws IOException {
        String id = Query.required(exchange, "id");
        Optional<PolishView> view = jobs == null ? Optional.empty() : jobs.polishView(id);
        if (view.isEmpty()) {
            Http.json(exchange, 404, Map.of("error", jobs == null ? NO_CLI : Projects.unknown(id)));
            return;
        }
        // The board is null until the vendors have been asked: a state the page shows, not an error.
        Http.json(exchange, 200, view.get());
    }

    private void ask(HttpExchange exchange) throws IOException {
        PolishRequest request = Http.bodyAs(exchange, PolishRequest.class);
        Http.json(exchange, 200, Map.of("result", jobs == null
                ? NO_CLI
                : jobs.askForDesigns(request.getId(), request.getNote(), request.getBase(), request.getExtra())));
    }

    private void build(HttpExchange exchange) throws IOException {
        PolishRequest request = Http.bodyAs(exchange, PolishRequest.class);
        Http.json(exchange, 200, Map.of("result", jobs == null
                ? NO_CLI
                : jobs.buildDesign(request.getId(), request.getOption(), request.getNote(), request.getExtra())));
    }

    private static void page(HttpExchange exchange) throws IOException {
        try (InputStream in = PolishRoutes.class.getResourceAsStream("/ui/polish.html")) {
            if (in == null) {
                Http.error(exchange, 500, "polish page missing from the jar");
                return;
            }
            Http.html(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
