package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.stream.Usage;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.ui.http.Http;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real server over real HTTP.
 *
 * <p>Written because the editor's failures have all been of a kind nothing else could catch: a
 * report link that silently served the dashboard, because the root context matches every path the
 * API does not. That bug was invisible to every other test here and took a person clicking it to
 * find. Anything reachable by URL is checkable in Java without a browser, so it should be.
 */
class UiServerTest {

    @TempDir
    Path root;

    private String base;
    private String wired;
    private Path runs;
    private QuotaLedger quota;
    private JobRunner runner;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void startServer() throws Exception {
        Path wishlist = Files.createDirectories(root.resolve("wishlist"));
        runs = Files.createDirectories(root.resolve("runs"));
        Files.writeString(wishlist.resolve("001-thing.md"), """
                ---
                id: "001"
                title: A thing
                priority: 1
                acceptance:
                  - mvn -q test
                ---
                Build the thing.
                """);
        // Port 0 so tests never collide with a running editor, or with each other.
        base = new UiServer(wishlist, runs, 0, null, null, null).start();

        // A second server, fully wired, so the job endpoints are exercised over real HTTP
        // rather than short-circuited by the null checks the bare one takes.
        Map<AgentKind, Agent> map =
                new EnumMap<>(AgentKind.class);
        Files.writeString(root.resolve("house-rules.md"), "# House rules\n");
        quota = new QuotaLedger(root.resolve("quota.json"));
        runner = new JobRunner(
                new ForgeHome(root),
                new Agents(map), 4, quota);
        wired = new UiServer(wishlist, runs, 0, null, runner, quota).start();
    }

    /**
     * Waits for any job a test started to finish, before the temporary directory is deleted.
     *
     * <p>An answer reaching the test is not its job finishing. A walkthrough answers at once and
     * records its {@code .wav} on a worker, a speech subprocess of a few seconds, which then rewrites
     * the report. Under load that was sometimes still writing into {@code runs\001} when JUnit came
     * to delete it, and a test whose every assertion had passed failed in its teardown.
     */
    @AfterEach
    void awaitJobs() throws InterruptedException {
        for (int i = 0; i < 1200 && !runner.settled(); i++) {
            Thread.sleep(50);
        }
        assertTrue(runner.settled(), "a job was still running a minute after the test ended");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Against the fully wired server, where jobs and quota are real. */
    private HttpResponse<String> live(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(wired + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postBare(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(wired + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the dashboard is served at the root")
    void servesTheDashboard() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>Forge"), "expected the editor page");
    }

    @Test
    @DisplayName("a run's report is served, not quietly replaced by the dashboard")
    void servesARunReport() throws Exception {
        Files.createDirectories(runs.resolve("001"));
        Files.writeString(runs.resolve("001/report.html"), "<title>A thing — Forge</title>");

        HttpResponse<String> response = get("/runs/001/report.html");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("A thing"), "got the dashboard instead of the report");
        assertFalse(response.body().contains("New spec"), "this is the dashboard, not the report");
    }

    @Test
    @DisplayName("evidence images are served with an image content type")
    void servesEvidenceWithTheRightType() throws Exception {
        Files.createDirectories(runs.resolve("001/evidence"));
        Files.write(runs.resolve("001/evidence/shot.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        HttpResponse<String> response = get("/runs/001/evidence/shot.png");

        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    @DisplayName("a design mock-up is served sealed: no scripts, and nothing it names can be loaded")
    void designPagesCannotReachTheNetwork() throws Exception {
        // The check that drops a page naming the network is a list of the ways somebody thought of,
        // and the first one missed srcset. The browser is told to refuse every load instead.
        Files.createDirectories(runs.resolve("001/design/CODEX"));
        Files.writeString(runs.resolve("001/design/CODEX/option-1.html"), "<html><body>neon</body></html>");
        Files.createDirectories(runs.resolve("001/repo"));
        Files.writeString(runs.resolve("001/repo/out.html"), "<html><body>the project's own page</body></html>");

        String design = get("/runs/001/design/CODEX/option-1.html").headers()
                .firstValue("Content-Security-Policy").orElse("");
        String output = get("/runs/001/repo/out.html").headers()
                .firstValue("Content-Security-Policy").orElse("");

        assertTrue(design.contains("default-src 'none'"), design);
        assertTrue(design.startsWith("sandbox;"), "and no allow-scripts: " + design);
        assertFalse(output.contains("default-src 'none'"),
                "a project's own output is sandboxed but may still load what it needs: " + output);
    }

    @Test
    @DisplayName("a missing run file is a 404, not the dashboard")
    void missingRunFileIsNotFound() throws Exception {
        assertEquals(404, get("/runs/001/nope.html").statusCode());
    }

    @Test
    @DisplayName("a path climbing out of the runs directory is refused")
    void refusesTraversal() throws Exception {
        Files.writeString(root.resolve("secret.txt"), "not yours");

        HttpResponse<String> response = get("/runs/../secret.txt");

        assertFalse(response.body().contains("not yours"), "served a file outside runs/");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a junction inside a run does not serve what is outside it")
    void refusesFilesReachedThroughJunctions() throws Exception {
        Path outside = Files.createDirectories(root.resolve("home"));
        Files.writeString(outside.resolve("secret.txt"), "not yours");
        Path repo = Files.createDirectories(runs.resolve("001/repo"));
        Path link = repo.resolve("leak");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), outside.toString()).redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        assertEquals(0, mklink.waitFor());
        try {
            HttpResponse<String> response = get("/runs/001/repo/leak/secret.txt");

            assertFalse(response.body().contains("not yours"), "served a file through a junction");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    @DisplayName("moving a spec up rewrites the ranking on disk, and the list stays contiguous")
    void rankMovesASpec() throws Exception {
        Path wishlist = root.resolve("wishlist");
        Files.writeString(wishlist.resolve("002-other.md"), """
                ---
                id: "002"
                title: Another thing
                priority: 7
                acceptance:
                  - mvn -q test
                ---
                Build the other thing.
                """);

        HttpResponse<String> response = postBare("/api/rank", "{\"id\":\"002\",\"delta\":-1}");

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Integer> rank = new HashMap<>();
        SpecLoader.loadAll(wishlist)
                .forEach(spec -> rank.put(spec.id(), spec.priority()));
        assertTrue(rank.get("002") < rank.get("001"), "002 was not moved above 001: " + rank);
        assertEquals(Set.of(1, 2), new HashSet<>(rank.values()),
                "a gap or a duplicate makes the next move ambiguous: " + rank);
    }

    @Test
    @DisplayName("what each vendor's window has spent is served, as recorded")
    void servesUsage() throws Exception {
        UsageLedger ledger =
                new UsageLedger(root.resolve("usage.json"));
        ledger.record(AgentKind.CLAUDE,
                new Usage(100, 9000, 900, 1.25));

        HttpResponse<String> response = get("/api/usage");

        assertEquals(200, response.statusCode());
        var claude = Http.mapper().readTree(response.body()).get("CLAUDE");
        assertEquals(1, claude.get("turns").asInt(), response.body());
        assertEquals(9000, claude.get("cachedTokens").asLong());
        assertEquals(1.25, claude.get("costUsd").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("the board's whole token spend is served, with the part no model is named for")
    void servesTokenTotals() throws Exception {
        // Deliberately not the same question as /api/usage above: that is the vendor's window,
        // which resets, and this is the work, which does not. A project whose split covers only
        // part of its total is the normal case, not an edge one.
        RunState state = new RunState();
        state.setId("001");
        state.setTokensSpent(50_000);
        state.setTokensByModel(Map.of("CLAUDE/sonnet", 20_000L));
        RunStore.save(RunPaths.under(runs, "001").stateFile(), state);

        HttpResponse<String> response = get("/api/tokens");

        assertEquals(200, response.statusCode());
        var totals = Http.mapper().readTree(response.body());
        assertEquals(50_000, totals.get("total").asLong(), response.body());
        assertEquals(1, totals.get("projects").asInt());
        assertEquals(30_000, totals.get("unattributed").asLong());
        assertEquals("sonnet", totals.get("byModel").get(0).get("model").asText());
        assertEquals(20_000, totals.get("byModel").get(0).get("tokens").asLong());
    }

    @Test
    @DisplayName("the spec list reports what is on disk")
    void listsSpecs() throws Exception {
        HttpResponse<String> response = get("/api/list");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("A thing"), response.body());
    }

    @Test
    @DisplayName("the job list is valid JSON even with no agent CLI configured")
    void jobsSurviveWithoutAgents() throws Exception {
        HttpResponse<String> response = get("/api/jobs");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().strip().startsWith("["), response.body());
    }

    @Test
    @DisplayName("capabilities report what is actually wired up")
    void reportsCapabilities() throws Exception {
        String body = get("/api/capabilities").body();

        assertTrue(body.contains("\"suggest\" : false"), body);
        assertTrue(body.contains("\"jobs\" : false"), body);
    }

    @Test
    @DisplayName("a handler that throws answers with an error rather than an empty response")
    void handlerFailuresAreReported() throws Exception {
        // No ?file= parameter, which the handler rejects.
        HttpResponse<String> response = get("/api/open");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("error"), response.body());
    }

    @Test
    @DisplayName("every page is served no-store, since it reflects files that change under it")
    void nothingIsCached() throws Exception {
        assertEquals("no-store", get("/").headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    @DisplayName("the API mapper can write the time types the domain keeps growing")
    void apiMapperHandlesTemporalTypes() throws Exception {
        // The assumption this replaces was never written down: that nothing temporal would be
        // served over the API. It stopped being true when QuotaWindow gained two Instants,
        // and the failure was invisible — Jackson throws midway, so the endpoint still answers
        // 200 with a truncated body and the browser renders nothing.
        String written = Http.mapper().writeValueAsString(
                Map.of("at", Instant.parse("2026-09-10T08:00:00Z")));

        assertTrue(written.contains("2026-09-10"), written);
    }

    @Test
    @DisplayName("refining a section is refused, not attempted, with no CLI installed")
    void refineNeedsAnAgent() throws Exception {
        HttpResponse<String> response = postBare("/api/refine",
                "{\"target\":\"Goal\",\"goal\":\"a\",\"nonGoals\":\"b\",\"notes\":\"c\"}");

        // The bare server has no Suggester, which is the shape of a machine where neither CLI is
        // installed. It must say so rather than answering with an empty suggestion.
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("suggestions are unavailable"), response.body());
    }

    // ------------------------------------------------------- job endpoints

    @Test
    @DisplayName("the job list is served as JSON, one row per spec")
    void servesJobs() throws Exception {
        HttpResponse<String> response = live("/api/jobs");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\" : \"001\""), response.body());
        assertTrue(response.body().contains("\"phase\""), response.body());
    }

    @Test
    @DisplayName("a refused control answers 200 with its reason, not an error status")
    void refusalsAreCarriedInTheBody() throws Exception {
        // The report shows the message beside the submit button, so a refusal has to arrive as
        // text rather than as a status code the fetch wrapper would turn into a generic failure.
        HttpResponse<String> response = post("/api/jobs/decide",
                "{\"id\":\"001\",\"decisions\":[{\"itemId\":\"a\",\"verdict\":\"ok\"}]}");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("has not run yet"), response.body());
    }

    @Test
    @DisplayName("a submitted review reaches the runner with its items intact")
    void decisionsAreParsed() throws Exception {
        HttpResponse<String> response = post("/api/jobs/decide",
                "{\"id\":\"nope\",\"decisions\":[{\"itemId\":\"unproven-0\","
                        + "\"text\":\"the draw branch\",\"verdict\":\"changes\","
                        + "\"comment\":\"render it\"}]}");

        // A malformed body would surface as a 500 from the guard rather than this refusal.
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("no spec with id nope"), response.body());
    }

    @Test
    @DisplayName("quota state is served, and reflects what the ledger recorded")
    void servesQuota() throws Exception {
        assertEquals("{ }", live("/api/quota").body().strip());

        quota.record(AgentKind.CLAUDE,
                Instant.now().plusSeconds(3600), "you have hit your usage limit");

        String body = live("/api/quota").body();
        assertTrue(body.contains("CLAUDE"), body);
        assertTrue(body.contains("usage limit"), body);
    }

    @Test
    @DisplayName("a vendor's hold can be let go on a person's word, and the next call finds out whether it is back")
    void aHoldCanBeLetGo() throws Exception {
        // Codex was reset at the vendor on a Tuesday and held here until the Friday: the ledger lets
        // a hold go only on a completed call, and nothing calls a vendor that is held.
        quota.record(AgentKind.CODEX, Instant.now().plusSeconds(3 * 86400), "You've hit your usage limit");

        HttpResponse<String> response = post("/api/quota/retry?vendor=codex", "{}");

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("CODEX is no longer held"), response.body());
        assertTrue(quota.blockedUntil(AgentKind.CODEX).isEmpty());
        assertTrue(post("/api/quota/retry?vendor=CODEX", "{}").body().contains("CODEX was not held"));
        assertEquals(400, post("/api/quota/retry?vendor=nobody", "{}").statusCode());
    }

    @Test
    @DisplayName("auto-run is reported as off and can be switched on")
    void autoRunIsReadableAndWritable() throws Exception {
        assertTrue(live("/api/auto").body().contains("\"autoRun\" : false"));

        // Switching auto-run on immediately fills capacity, so both vendors are closed first.
        // Otherwise this test would start a real project on a real CLI, which is precisely the
        // behaviour that makes auto-run default to off in the first place.
        Instant later = Instant.now().plusSeconds(3600);
        quota.record(AgentKind.CLAUDE, later, "not during a test");
        quota.record(AgentKind.CODEX, later, "not during a test");

        post("/api/auto", "{\"enabled\":true}");

        assertTrue(live("/api/auto").body().contains("\"autoRun\" : true"));
    }

    @Test
    @DisplayName("a project answers with what to say about it, and leaves the transcript beside the run")
    void walkthroughIsSpokenAndWrittenDown() throws Exception {
        RunState state = new RunState();
        state.setId("001");
        state.setTitle("A thing");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);
        RunPaths paths = RunPaths.under(runs, "001");
        RunStore.save(paths.stateFile(), state);
        // The repository every finished project has: without it Forge rightly reads it as not here.
        Files.createDirectories(paths.repo().resolve(".git"));

        String body = post("/api/jobs/walkthrough", "{\"id\":\"001\"}").body();

        assertTrue(body.contains("A thing"), body);
        assertTrue(body.contains("waiting for you to look at it"), body);
        assertTrue(Files.readString(paths.walkthroughFile()).contains("A thing"),
                "the words should be on disk to read as well as hear");
        assertTrue(body.contains("runs/001/walkthrough.wav"), "and the recording's place is named: " + body);
    }

    @Test
    @DisplayName("a project that has never run says so, rather than answering with nothing")
    void walkthroughOfAProjectThatNeverRan() throws Exception {
        String body = post("/api/jobs/walkthrough", "{\"id\":\"001\"}").body();

        assertTrue(body.contains("has not run yet"), body);
        assertTrue(body.contains("\"audio\" : \"\""), "nothing was recorded, and it does not pretend: " + body);
    }
}
