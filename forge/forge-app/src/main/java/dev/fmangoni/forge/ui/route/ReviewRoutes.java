package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.report.deck.DeckView;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The quick review: its page, and the cards it deals.
 *
 * <p>Both are reads. The page submits to the same {@code /api/jobs/decide} the report's form uses,
 * so there is one place a review is accepted and one set of rules about what it may do.
 */
public final class ReviewRoutes {

    /** Null when no CLI is installed, as for every other route that needs the projects. */
    private final JobRunner jobs;

    public ReviewRoutes(JobRunner jobs) {
        this.jobs = jobs;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/review", ReviewRoutes::page);
        endpoints.read("/api/review", this::deck);
    }

    private void deck(HttpExchange exchange) throws IOException {
        String id = Query.required(exchange, "id");
        Optional<DeckView> deck = jobs == null ? Optional.empty() : jobs.reviewDeck(id);
        if (deck.isEmpty()) {
            Http.json(exchange, 404, Map.of("error", jobs == null
                    ? "no agent CLI installed"
                    : "there is no project " + id + " to review, or it has not run yet"));
            return;
        }
        Http.json(exchange, 200, deck.get());
    }

    private static void page(HttpExchange exchange) throws IOException {
        try (InputStream in = ReviewRoutes.class.getResourceAsStream("/ui/review.html")) {
            if (in == null) {
                Http.error(exchange, 500, "review page missing from the jar");
                return;
            }
            Http.html(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
