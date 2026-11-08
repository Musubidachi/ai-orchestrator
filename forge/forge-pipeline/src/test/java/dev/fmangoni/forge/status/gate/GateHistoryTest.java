package dev.fmangoni.forge.status.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gates on a version ({@code GateHistory}): each noted as it finishes, read back with the last
 * gate, and what the panel says from them.
 */
class GateHistoryTest {

    private static final String BUILD = "npm run build";
    private static final String TEST = "npm test";
    private static final String E2E = "npx playwright install chromium && npm run test:e2e";
    private static final List<String> THREE = List.of(BUILD, TEST, E2E);

    @TempDir
    Path root;

    @Test
    @DisplayName("a quick gate is noted with the session it followed and the time it did not spend waiting")
    void quickGateNoted() {
        RunState state = quickPass(4);

        GateHistory.note(root, THREE, state);

        List<PastGate> gates = GateHistory.load(root);
        assertEquals(1, gates.size());
        PastGate gate = gates.getFirst();
        assertEquals(4, gate.session());
        assertTrue(gate.quick());
        assertTrue(gate.passed());
        assertEquals("", gate.failed());
        assertEquals(2, gate.seconds());
        // The browser suite usually takes 380 seconds, and this gate left it for the full one.
        assertEquals(380, gate.spared());
    }

    @Test
    @DisplayName("a failed gate names the command it stopped at, or the check")
    void failuresNamed() {
        RunState state = timed(Map.of(BUILD, List.of(1), TEST, List.of(3), E2E, List.of(370)));
        state.setSessions(2);
        state.setLastFailure(failure(TEST));
        GateHistory.note(root, THREE, state);
        state.setSessions(3);
        state.setLastFailure(failure("run file: run.bat"));
        GateHistory.note(root, THREE, state);

        List<PastGate> gates = GateHistory.load(root);
        assertEquals(List.of(TEST, "run file: run.bat"), gates.stream().map(PastGate::failed).toList());
        assertFalse(gates.getFirst().passed());
        assertFalse(gates.getFirst().quick());
        assertEquals(0, gates.getFirst().spared());
    }

    @Test
    @DisplayName("the panel gets the gates before the last one, and the session the last one followed")
    void readWithTheLastGate() throws Exception {
        Path wishlist = Files.createDirectories(root.resolve("wishlist"));
        Path runs = Files.createDirectories(root.resolve("runs"));
        spec(wishlist);
        RunPaths paths = RunPaths.under(runs, "001");
        RunState state = quickPass(1);
        GateHistory.note(paths.root(), THREE, state);
        state.setSessions(2);
        GateHistory.note(paths.root(), THREE, state);
        // Session 3 has finished and its gate is running: the last gate is still the one after 2.
        state.setSessions(3);
        state.setPhase(Phase.GATING);
        RunStore.save(paths.stateFile(), state);

        LastGate gate = LastGateReader.read(runs, wishlist, "001");

        assertEquals(2, gate.after());
        assertEquals(List.of(1, 2), gate.history().stream().map(PastGate::session).toList());
    }

    @Test
    @DisplayName("a version whose gates ran before they were noted takes the sessions so far, unless its gate is running")
    void beforeAnyWasNoted() throws Exception {
        Path wishlist = Files.createDirectories(root.resolve("wishlist"));
        Path runs = Files.createDirectories(root.resolve("runs"));
        spec(wishlist);
        RunPaths paths = RunPaths.under(runs, "001");
        RunState state = quickPass(7);
        state.setPhase(Phase.CONTINUING);
        RunStore.save(paths.stateFile(), state);

        assertEquals(7, LastGateReader.read(runs, wishlist, "001").after());
        assertEquals(List.of(), LastGateReader.read(runs, wishlist, "001").history());

        state.setPhase(Phase.GATING);
        RunStore.save(paths.stateFile(), state);
        assertEquals(0, LastGateReader.read(runs, wishlist, "001").after());
    }

    @Test
    @DisplayName("a version that starts over drops the gates of the run before")
    void startedOver() {
        GateHistory.note(root, THREE, quickPass(9));
        GateHistory.note(root, THREE, quickPass(1));

        assertEquals(List.of(1), GateHistory.load(root).stream().map(PastGate::session).toList());
    }

    @Test
    @DisplayName("only the latest gates are kept, oldest first")
    void keptToTheLatest() {
        for (int session = 1; session <= GateHistory.KEPT + 5; session++) {
            GateHistory.note(root, THREE, quickPass(session));
        }

        List<PastGate> gates = GateHistory.load(root);
        assertEquals(GateHistory.KEPT, gates.size());
        assertEquals(6, gates.getFirst().session());
        assertEquals(GateHistory.KEPT + 5, gates.getLast().session());
    }

    @Test
    @DisplayName("nothing is noted before a gate has run, and a file that cannot be read is started again")
    void nothingOrUnreadable() throws Exception {
        GateHistory.note(root, THREE, new RunState());
        assertFalse(Files.exists(root.resolve(GateHistory.FILE)));

        Files.writeString(root.resolve(GateHistory.FILE), "{not json");
        assertEquals(List.of(), GateHistory.load(root));
        GateHistory.note(root, THREE, quickPass(3));
        assertEquals(List.of(3), GateHistory.load(root).stream().map(PastGate::session).toList());
    }

    // ---------------------------------------------------------------- fixtures

    /** A quick gate that passed after {@code session}: the browser suite waits for the full gate. */
    private static RunState quickPass(int session) {
        RunState state = timed(Map.of(BUILD, List.of(1, 1), TEST, List.of(1, 2, 1), E2E, List.of(380, 375, 390)));
        state.setNotRunAtLastGate(new ArrayList<>(List.of(E2E)));
        state.setSessions(session);
        return state;
    }

    private static RunState timed(Map<String, List<Integer>> seconds) {
        RunState state = new RunState();
        state.setCommandSeconds(new LinkedHashMap<>(seconds));
        return state;
    }

    private static CommandResult failure(String command) {
        return new CommandResult(command, 1, "", false, Duration.ZERO, "");
    }

    private static void spec(Path wishlist) throws Exception {
        Files.writeString(wishlist.resolve("001-thing.md"), """
                ---
                id: "001"
                title: Thing
                priority: 1
                acceptance:
                  - %s
                  - %s
                  - "%s"
                ---
                Build it.
                """.formatted(BUILD, TEST, E2E));
    }
}
