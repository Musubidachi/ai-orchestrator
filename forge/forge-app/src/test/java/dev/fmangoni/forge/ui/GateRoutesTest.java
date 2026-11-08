package dev.fmangoni.forge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.project.ForgeHome;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's last gate over real HTTP ({@code route.GateRoutes}): the shape the gate section of the
 * panel ({@code extras/gate.js}) reads.
 *
 * <p>What each command's part is comes from {@code LastGateTest}, in forge-pipeline. These are about
 * the page reaching it: the names it reads, and what it gets for a project with nothing to show.
 */
class GateRoutesTest {

    private static final String BUILD = "mvn -q compile";
    private static final String E2E = "npm run test:e2e";

    @TempDir
    Path root;

    private ForgeHome home;
    private String server;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void startServer() throws Exception {
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
                  - %s
                  - %s
                ---
                Build the thing.
                """.formatted(BUILD, E2E));
        // Port 0 so tests never collide with a running editor. The gate needs no CLI: it only reads.
        server = new UiServer(home.wishlist(), home.runs(), 0, null, null, null).start();
    }

    @Test
    @DisplayName("a quick gate comes back with each command's part and its times")
    void quickGate() throws Exception {
        RunState state = new RunState();
        state.setId("001");
        state.setCommandSeconds(new LinkedHashMap<>(Map.of(BUILD, List.of(5, 3), E2E, List.of(300, 320, 310))));
        state.setNotRunAtLastGate(new ArrayList<>(List.of(E2E)));
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), state);

        HttpResponse<String> response = get("/api/gate?id=001");
        JsonNode gate = Json.mapper().readTree(response.body());

        assertEquals(200, response.statusCode());
        assertTrue(gate.path("ran").asBoolean(), response.body());
        assertTrue(gate.path("quick").asBoolean(), response.body());
        assertTrue(gate.path("passed").asBoolean(), response.body());
        assertEquals(3, gate.path("seconds").asInt(), response.body());
        assertEquals(120, gate.path("slowSeconds").asInt(), response.body());
        JsonNode slow = gate.path("commands").path(1);
        assertEquals(E2E, slow.path("command").asText(), response.body());
        assertEquals("waits", slow.path("state").asText(), response.body());
        assertEquals(310, slow.path("seconds").asInt(), response.body());
        assertEquals(310, slow.path("usual").asInt(), response.body());
    }

    @Test
    @DisplayName("a project with no gate yet, or none at all, has nothing to show; no id is refused")
    void nothingToShow() throws Exception {
        assertFalse(Json.mapper().readTree(get("/api/gate?id=001").body()).path("ran").asBoolean(true));
        assertFalse(Json.mapper().readTree(get("/api/gate?id=999").body()).path("ran").asBoolean(true));
        assertEquals(400, get("/api/gate").statusCode());
    }

    // ---------------------------------------------------------------- fixtures

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
