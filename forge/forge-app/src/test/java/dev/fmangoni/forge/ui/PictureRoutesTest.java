package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.Verdict;
import dev.fmangoni.forge.project.ForgeHome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Picture batches over real HTTP ({@code ui.pictures.PictureRoutes}): the shapes the dashboard's card
 * ({@code extras/pictures.js}) and the review page ({@code pictures.html}) read and send.
 *
 * <p>What a ruling or a placing does is {@code PictureFlowTest}'s, in forge-pipeline. These are about
 * the pages reaching it.
 */
class PictureRoutesTest {

    private static final String ID = "042-pictures";

    private static final String MARKET = "repo/drafts/market.png";

    private static final String CARD = """
            ## Job 1 of 1: Lantern Fair, Market

            Prompt:

            ```text
            Paint the market at dusk.
            ```

            Save the picture as `repo/drafts/market.png`, then run:

            ```bat
            echo ok
            ```

            Once kept, Forge places it with:

            ```bat
            echo placed
            ```
            """;

    @TempDir
    Path root;

    private Path batch;

    private String server;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void startServer() throws Exception {
        ForgeHome home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.createDirectories(home.runs());
        Files.writeString(home.houseRulesFile(), "# House rules\n");
        batch = Files.createDirectories(home.runs().resolve(ID));
        Files.writeString(batch.resolve("batch.json"), "{\"title\": \"Lantern screens\", \"target\": \"042\"}");
        Files.writeString(batch.resolve("job-cards.md"), CARD);
        server = new UiServer(home.wishlist(), home.runs(), 0, null, null, null).start();
    }

    @Test
    @DisplayName("the card reads every batch with its counts, its jobs and the message for the agent")
    void listed() throws Exception {
        HttpResponse<String> response = get("/api/pictures");
        JsonNode first = Json.mapper().readTree(response.body()).path(0);

        assertEquals(200, response.statusCode());
        assertEquals(ID, first.path("id").asText(), response.body());
        assertEquals("Lantern screens", first.path("title").asText(), response.body());
        assertEquals("Astra", first.path("agent").asText(), response.body());
        assertEquals(1, first.path("counts").path("TO_DRAW").asInt(), response.body());
        assertEquals("Paint the market at dusk.", first.path("jobs").path(0).path("prompt").asText(), response.body());
        assertTrue(first.path("message").asText().contains("next-jobs.md"), response.body());
    }

    @Test
    @DisplayName("one batch comes back by its id; an unknown one is not found and no id is refused")
    void one() throws Exception {
        assertEquals(ID, Json.mapper().readTree(get("/api/pictures/batch?id=" + ID).body()).path("id").asText());
        assertEquals(404, get("/api/pictures/batch?id=999-none").statusCode());
        assertEquals(400, get("/api/pictures/batch").statusCode());
    }

    @Test
    @DisplayName("the review page is served")
    void page() throws Exception {
        HttpResponse<String> response = get("/pictures?id=" + ID);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/api/pictures"), "it is the page that reads the batches");
    }

    @Test
    @DisplayName("a review saves what it can and says why for the rest; a click asks once for each kept picture")
    void reviewAndPlace() throws Exception {
        Path market = Files.createDirectories(batch.resolve("repo/drafts")).resolve("market.png");
        Files.writeString(market, "market");
        String stamp = Json.mapper().readTree(get("/api/pictures/batch?id=" + ID).body())
                .path("jobs").path(0).path("stamp").asText();
        new RecordStore(batch).update(record -> record.edit(MARKET).getChecks()
                .add(new CommandRun(stamp, Instant.now(), 0, Verdict.OK, List.of("ok"), null)));

        HttpResponse<String> ruled = post("/api/pictures/rule", """
                {"id": "%s", "rulings": [
                  {"draft": "%s", "stamp": "%s", "verdict": "keep"},
                  {"draft": "repo/drafts/none.png", "stamp": "1-1", "verdict": "keep"},
                  {"draft": "%s", "stamp": "%s", "verdict": "maybe"}]}
                """.formatted(ID, MARKET, stamp, MARKET, stamp));
        JsonNode answer = Json.mapper().readTree(ruled.body());

        assertEquals(200, ruled.statusCode(), ruled.body());
        assertEquals(1, answer.path("saved").asInt(), ruled.body());
        assertEquals("no job saves a picture as repo/drafts/none.png",
                answer.path("skipped").path("repo/drafts/none.png").asText(), ruled.body());
        assertEquals("say keep or redo", answer.path("skipped").path(MARKET).asText(), ruled.body());
        assertEquals("1 ruling saved; 2 not saved.", answer.path("result").asText(), ruled.body());
        assertEquals(1, Json.mapper().readTree(get("/api/pictures/batch?id=" + ID).body()).path("placeable").asInt());

        JsonNode placed = Json.mapper().readTree(post("/api/pictures/place", "{\"id\": \"" + ID + "\"}").body());
        assertEquals(1, placed.path("asked").asInt(), placed.toString());
        assertEquals("Placing 1 picture, one at a time.", placed.path("result").asText());
        JsonNode again = Json.mapper().readTree(post("/api/pictures/place", "{\"id\": \"" + ID + "\"}").body());
        assertEquals("Nothing kept is waiting to be placed.", again.path("result").asText(), again.toString());
        assertEquals(404, post("/api/pictures/place", "{\"id\": \"999-none\"}").statusCode());
    }

    // ---------------------------------------------------------------- fixtures

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
