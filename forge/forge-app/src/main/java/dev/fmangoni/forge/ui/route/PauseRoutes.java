package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import java.io.IOException;
import java.util.Map;

/**
 * Stop with a reason: the dialog's endpoint, and the notes the page shows on a paused project.
 *
 * <p>A pause's body is {@code {"id": "004", "reason": "Waiting for art"}}, read as a plain map so the
 * request needs no class of its own. What it does is {@code jobs.pause.Pausing}'s.
 */
public final class PauseRoutes {

    /** Null when no CLI is installed: then nothing is paused, and nothing can be. */
    private final JobRunner jobs;

    public PauseRoutes(JobRunner jobs) {
        this.jobs = jobs;
    }

    public void register(Endpoints endpoints) {
        endpoints.write("/api/jobs/pause", this::pause);
        endpoints.read("/api/pauses", exchange -> Http.json(exchange, 200, jobs == null ? Map.of() : jobs.pauses()));
    }

    private void pause(HttpExchange exchange) throws IOException {
        Map<?, ?> body = Http.bodyAs(exchange, Map.class);
        Object reason = body.get("reason");
        Http.json(exchange, 200, Map.of("result", jobs == null
                ? JobRoutes.NO_CLI
                : jobs.pause(String.valueOf(body.get("id")), reason == null ? "" : String.valueOf(reason))));
    }
}
