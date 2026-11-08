package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stop with a reason over real HTTP ({@code route.PauseRoutes}): the dialog's endpoint, and the notes
 * the page reads back.
 *
 * <p>What a pause holds and what it refuses is {@code PauseTest}'s, in forge-pipeline. These are about
 * the page reaching it: the body it sends, the words it gets back, and a time it can read as a date.
 */
class PauseRoutesTest {

    private static final String PAUSED = "paused; the queue leaves it alone until you press Resume Now";

    @TempDir
    Path root;

    private ForgeHome home;
    private String bare;
    private String wired;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void startServers() throws Exception {
        home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.createDirectories(home.runs());
        Files.writeString(home.houseRulesFile(), "# House rules\n");
        Files.writeString(home.wishlist().resolve("001-thing.md"), """
                ---
                id: "001"
                title: A thing
                priority: 1
                acceptance:
                  - mvn -q test
                ---
                Build the thing.
                """);
        // Port 0 so tests never collide with a running editor, or with each other. The bare server
        // is the editor with no CLI installed; the wired one has jobs, but no agent to start.
        bare = new UiServer(home.wishlist(), home.runs(), 0, null, null, null).start();
        QuotaLedger quota = new QuotaLedger(root.resolve("quota.json"));
        JobRunner runner = new JobRunner(home, new Agents(new EnumMap<>(AgentKind.class)), 4, quota);
        wired = new UiServer(home.wishlist(), home.runs(), 0, null, runner, quota).start();
    }

    @Test
    @DisplayName("with no CLI installed nothing is paused, and a pause says why it cannot be")
    void withoutACli() throws Exception {
        HttpResponse<String> notes = get(bare, "/api/pauses");
        HttpResponse<String> pause = post(bare, "{\"id\":\"001\",\"reason\":\"Waiting for art\"}");

        assertEquals(200, notes.statusCode());
        assertEquals(0, Json.mapper().readTree(notes.body()).size(), notes.body());
        assertEquals(200, pause.statusCode());
        assertEquals("no agent CLI installed", result(pause), pause.body());
    }

    @Test
    @DisplayName("a pause is answered in words, and its reason and time are read back")
    void pausesAndReadsBack() throws Exception {
        runIn("001", Phase.SCAFFOLDED);

        HttpResponse<String> pause = post(wired, "{\"id\":\"001\",\"reason\":\"Waiting for art\"}");

        assertEquals(200, pause.statusCode());
        assertEquals(PAUSED, result(pause), pause.body());
        JsonNode note = Json.mapper().readTree(get(wired, "/api/pauses").body()).path("001");
        assertEquals("Waiting for art", note.path("reason").asText(), note.toString());
        // The page makes a Date of it: an ISO instant, never a count of seconds.
        Instant at = Instant.parse(note.path("at").asText());
        assertTrue(at.isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)), String.valueOf(at));
    }

    @Test
    @DisplayName("a reminder is sent as a day and read back as one, and one that is not a day is refused")
    void reminders() throws Exception {
        runIn("001", Phase.SCAFFOLDED);

        assertEquals("the reminder must be a day, such as 2026-11-14",
                result(post(wired, "{\"id\":\"001\",\"reason\":\"x\",\"remind\":\"Saturday\"}")));
        assertEquals(PAUSED, result(post(wired, "{\"id\":\"001\",\"reason\":\"x\",\"remind\":\"2026-11-14\"}")));

        // The page compares it with today's date as text, so it must come back as one, not as numbers.
        JsonNode note = Json.mapper().readTree(get(wired, "/api/pauses").body()).path("001");
        assertEquals("2026-11-14", note.path("remind").asText(), note.toString());
    }

    @Test
    @DisplayName("a pause sent with no reason is held all the same, and an unknown id is refused")
    void withoutAReason() throws Exception {
        runIn("001", Phase.SCAFFOLDED);

        assertEquals(PAUSED, result(post(wired, "{\"id\":\"001\"}")));
        assertEquals("no spec with id 999", result(post(wired, "{\"id\":\"999\",\"reason\":\"x\"}")));

        JsonNode notes = Json.mapper().readTree(get(wired, "/api/pauses").body());
        assertEquals(1, notes.size(), notes.toString());
        assertEquals("", notes.path("001").path("reason").asText("missing"), notes.toString());
    }

    // ---------------------------------------------------------------- fixtures

    /** A run on disk in a given phase, with the repository a project that has begun has. */
    private void runIn(String id, Phase phase) throws Exception {
        RunPaths paths = RunPaths.under(home.runs(), id);
        RunState state = new RunState();
        state.setId(id);
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        RunStore.save(paths.stateFile(), state);
        Files.createDirectories(paths.repo().resolve(".git"));
    }

    private HttpResponse<String> get(String server, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String server, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + "/api/jobs/pause"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String result(HttpResponse<String> response) throws Exception {
        return Json.mapper().readTree(response.body()).path("result").asText();
    }
}
