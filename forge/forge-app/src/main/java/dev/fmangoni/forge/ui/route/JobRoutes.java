package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.jobs.button.Narration;
import dev.fmangoni.forge.status.league.League;
import dev.fmangoni.forge.status.spend.Spend;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.request.AutoRequest;
import dev.fmangoni.forge.ui.request.DecisionRequest;
import dev.fmangoni.forge.ui.request.FeatureRequest;
import dev.fmangoni.forge.ui.request.JobRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The dashboard's endpoints: the project list, and every button on a project's card. */
public final class JobRoutes {

    private static final String NO_CLI = "no agent CLI installed";

    /** Null when no CLI is installed; the dashboard then shows state without offering to run anything. */
    private final JobRunner jobs;

    /** Where the runs are, which is all the league table reads. */
    private final Path runsDir;

    public JobRoutes(JobRunner jobs, Path runsDir) {
        this.jobs = jobs;
        this.runsDir = runsDir;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/api/jobs", exchange -> Http.json(exchange, 200, jobs == null ? List.of() : jobs.statuses()));
        endpoints.write("/api/jobs/start", action(JobRunner::start));
        endpoints.write("/api/jobs/stop", action(JobRunner::stop));
        endpoints.write("/api/jobs/reopen", action(JobRunner::reopen));
        endpoints.write("/api/jobs/decide", this::decide);
        endpoints.write("/api/jobs/review", action(JobRunner::rereview));
        // Starts an agent session on the project's blocking findings; a write like the rest.
        endpoints.write("/api/jobs/fix", action(JobRunner::fixBlocking));
        // Runs a parked project's gate and nothing else: no agent, no session spent.
        endpoints.write("/api/jobs/recheck", action(JobRunner::recheck));
        // One feature's ruling from the dashboard. Saves it and starts nothing.
        endpoints.write("/api/jobs/feature", this::feature);
        // A screenshot pasted or picked into a note, saved where the project's agent can open it.
        // Starts nothing; answers with the text the page puts in the note.
        endpoints.write("/api/jobs/attach", this::attach);
        endpoints.read("/attach.js", JobRoutes::attachScript);
        // Publishes the project. Guarded as a write like every other action, and confirmed on the
        // page with the exact command before this is ever called.
        endpoints.write("/api/jobs/deploy", action(JobRunner::deploy));
        // Approving a version publishes it; this is Publish pressed by hand, to try again.
        endpoints.write("/api/jobs/publish", action(JobRunner::publish));
        endpoints.write("/api/jobs/marketing", action(JobRunner::marketing));
        endpoints.write("/api/jobs/security", action(JobRunner::rescan));
        // Opens the project's run.bat in a window of its own: a write, because it starts something.
        endpoints.write("/api/jobs/play", action(JobRunner::play));
        // Read from the runs on disk, so it answers with no agent CLI installed too.
        endpoints.read("/api/league", exchange -> Http.json(exchange, 200, League.of(runsDir)));
        // What the whole board has spent, and on which models. Also read from the runs, and so
        // not to be confused with /api/usage, which is the vendors' own windows.
        endpoints.read("/api/tokens", exchange -> Http.json(exchange, 200, Spend.of(runsDir)));
        // A write: it puts a transcript and a recording in the run's directory.
        endpoints.write("/api/jobs/walkthrough", this::walkthrough);
        endpoints.readOrWrite("/api/auto", this::auto);
    }

    /** A button that names one project and answers with what happened to it. */
    private HttpHandler action(BiFunction<JobRunner, String, String> act) {
        return exchange -> Http.json(exchange, 200, Map.of("result", jobs == null
                ? NO_CLI
                : act.apply(jobs, Http.bodyAs(exchange, JobRequest.class).getId())));
    }

    private void feature(HttpExchange exchange) throws IOException {
        FeatureRequest request = Http.bodyAs(exchange, FeatureRequest.class);
        Http.json(exchange, 200, Map.of("result", jobs == null
                ? NO_CLI
                : jobs.ruleFeature(request.getId(), request.getItemId(), request.getVerdict(),
                        request.getSameAs(), request.getNote())));
    }

    /**
     * Body: {@code {"id": "004", "data": "<base64, or a data: URL>"}}. Read as a plain map so the
     * request needs no class of its own; the image itself is judged by {@code Attachments}.
     */
    private void attach(HttpExchange exchange) throws IOException {
        Map<?, ?> body = Http.bodyAs(exchange, Map.class);
        if (jobs == null) {
            Http.json(exchange, 200, Map.of("error", NO_CLI));
            return;
        }
        String data = String.valueOf(body.get("data") == null ? "" : body.get("data"));
        int comma = data.startsWith("data:") ? data.indexOf(',') : -1;
        try {
            byte[] image = Base64.getDecoder().decode(data.substring(comma + 1).strip());
            Http.json(exchange, 200, Map.of("reference",
                    jobs.attach(String.valueOf(body.get("id")), image)));
        } catch (IllegalArgumentException e) {
            Http.json(exchange, 200, Map.of("error", e.getMessage() == null ? "not an image" : e.getMessage()));
        }
    }

    private static void attachScript(HttpExchange exchange) throws IOException {
        try (InputStream in = JobRoutes.class.getResourceAsStream("/ui/attach.js")) {
            if (in == null) {
                Http.error(exchange, 500, "attach.js missing from the jar");
                return;
            }
            Http.script(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void decide(HttpExchange exchange) throws IOException {
        DecisionRequest request = Http.bodyAs(exchange, DecisionRequest.class);
        Http.json(exchange, 200, Map.of("result", jobs == null
                ? NO_CLI
                : jobs.decide(request.getId(), request.getDecisions(), request.getFeatures())));
    }

    /**
     * What this project is, in words for the page to speak.
     *
     * <p>Answers with the walkthrough itself rather than a result line, because the page reads the
     * answer out; when there is nothing to say, the reason is what gets read.
     */
    private void walkthrough(HttpExchange exchange) throws IOException {
        if (jobs == null) {
            Http.json(exchange, 200, Map.of("text", NO_CLI, "audio", ""));
            return;
        }
        Narration narration = jobs.walkthrough(Http.bodyAs(exchange, JobRequest.class).getId());
        Http.json(exchange, 200, Map.of("text", narration.text(), "audio", narration.audio()));
    }

    /** Reads or sets automatic queue-filling. A GET reports it; a POST changes it. */
    private void auto(HttpExchange exchange) throws IOException {
        if (jobs == null) {
            Http.json(exchange, 200, Map.of("autoRun", false, "available", false));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            jobs.setAutoRun(Http.bodyAs(exchange, AutoRequest.class).isEnabled());
        }
        Http.json(exchange, 200, Map.of("autoRun", jobs.isAutoRun(), "available", true));
    }
}
