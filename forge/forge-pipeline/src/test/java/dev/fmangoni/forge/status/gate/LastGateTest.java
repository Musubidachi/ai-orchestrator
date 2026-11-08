package dev.fmangoni.forge.status.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.pipeline.step.QuickGate;
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
 * The last gate as a panel shows it ({@code GateSummary}, {@code LastGateReader}): which kind of gate
 * ran, and what it did with each command, from what the state recorded.
 */
class LastGateTest {

    private static final String BUILD = "npm run build";
    private static final String TEST = "npm test";
    private static final String E2E = "npx playwright install chromium && npm run test:e2e";
    private static final List<String> THREE = List.of(BUILD, TEST, E2E);

    @TempDir
    Path root;

    @Test
    @DisplayName("before any gate there is nothing to show")
    void noGateYet() {
        LastGate gate = GateSummary.of(THREE, new RunState());

        assertFalse(gate.ran());
        assertEquals(List.of(), gate.commands());
    }

    @Test
    @DisplayName("a quick gate ran the quick commands, and the slow one waits for the full gate")
    void quickGatePassed() {
        RunState state = timed(Map.of(BUILD, List.of(1, 1), TEST, List.of(1, 2, 1), E2E, List.of(380, 375, 390)));
        state.setNotRunAtLastGate(new ArrayList<>(List.of(E2E)));

        LastGate gate = GateSummary.of(THREE, state);

        assertTrue(gate.ran());
        assertTrue(gate.quick());
        assertTrue(gate.passed());
        assertEquals("", gate.check());
        // What this gate's commands took: the waiting one's time is from an earlier gate.
        assertEquals(2, gate.seconds());
        assertEquals(QuickGate.SLOW_SECONDS, gate.slowSeconds());
        assertEquals(List.of(
                new GateCommand(BUILD, GateCommand.PASSED, 1, 1),
                new GateCommand(TEST, GateCommand.PASSED, 1, 1),
                new GateCommand(E2E, GateCommand.WAITS, 390, 380)), gate.commands());
    }

    @Test
    @DisplayName("a full gate stops at the command that fails, and never reaches the ones after it")
    void fullGateFailedAtACommand() {
        RunState state = timed(Map.of(BUILD, List.of(1), TEST, List.of(3), E2E, List.of(370)));
        state.setLastFailure(failure(TEST));

        LastGate gate = GateSummary.of(THREE, state);

        assertFalse(gate.quick());
        assertFalse(gate.passed());
        assertEquals("", gate.check());
        assertEquals(4, gate.seconds());
        assertEquals(List.of(GateCommand.PASSED, GateCommand.FAILED, GateCommand.UNREACHED), parts(gate));
    }

    @Test
    @DisplayName("a gate whose commands all passed can still stop at one of its checks")
    void fullGateFailedAtACheck() {
        RunState state = timed(Map.of(BUILD, List.of(1), TEST, List.of(3), E2E, List.of(370)));
        state.setLastFailure(failure("run file: run.bat"));

        LastGate gate = GateSummary.of(THREE, state);

        assertFalse(gate.passed());
        assertEquals("run file: run.bat", gate.check());
        assertEquals(374, gate.seconds());
        assertEquals(List.of(GateCommand.PASSED, GateCommand.PASSED, GateCommand.PASSED), parts(gate));
    }

    @Test
    @DisplayName("a quick gate that fails still leaves the slow command waiting")
    void quickGateFailed() {
        RunState state = timed(Map.of(BUILD, List.of(2), TEST, List.of(1), E2E, List.of(370)));
        state.setNotRunAtLastGate(new ArrayList<>(List.of(E2E)));
        state.setLastFailure(failure(BUILD));

        LastGate gate = GateSummary.of(THREE, state);

        assertTrue(gate.quick());
        assertFalse(gate.passed());
        assertEquals(2, gate.seconds());
        assertEquals(List.of(GateCommand.FAILED, GateCommand.UNREACHED, GateCommand.WAITS), parts(gate));
    }

    @Test
    @DisplayName("a command that fails on its first run, with no time recorded, still shows as failed")
    void failedWithoutATime() {
        RunState state = timed(Map.of(BUILD, List.of(1)));
        state.setLastFailure(failure(TEST));

        LastGate gate = GateSummary.of(THREE, state);

        assertEquals(List.of(GateCommand.PASSED, GateCommand.FAILED, GateCommand.UNREACHED), parts(gate));
        assertEquals(1, gate.seconds());
    }

    @Test
    @DisplayName("a command the spec gained since the last gate has not run yet, and has no time")
    void newCommand() {
        String lint = "npm run lint";
        RunState state = timed(Map.of(BUILD, List.of(1), TEST, List.of(3)));

        LastGate gate = GateSummary.of(List.of(BUILD, lint, TEST), state);

        assertEquals(new GateCommand(lint, GateCommand.NEW, null, null), gate.commands().get(1));
        assertEquals(List.of(GateCommand.PASSED, GateCommand.NEW, GateCommand.PASSED), parts(gate));
        assertEquals(4, gate.seconds());
    }

    @Test
    @DisplayName("an enhancement's gate runs its ancestors' commands first, and an unknown id shows nothing")
    void readsTheSpecAndTheState() throws Exception {
        Path wishlist = Files.createDirectories(root.resolve("wishlist"));
        Path runs = Files.createDirectories(root.resolve("runs"));
        spec(wishlist, "001", "", BUILD);
        spec(wishlist, "001.1", "parent: \"001\"\n", TEST);
        RunState state = timed(Map.of(BUILD, List.of(4), TEST, List.of(6)));
        state.setId("001.1");
        RunStore.save(RunPaths.under(runs, "001.1").stateFile(), state);

        LastGate gate = LastGateReader.read(runs, wishlist, "001.1");

        assertTrue(gate.ran());
        assertEquals(List.of(BUILD, TEST), gate.commands().stream().map(GateCommand::command).toList());
        assertEquals(10, gate.seconds());
        // A spec with no run yet, and one that is not in the wishlist at all.
        assertFalse(LastGateReader.read(runs, wishlist, "001").ran());
        assertFalse(LastGateReader.read(runs, wishlist, "../001.1").ran());
    }

    // ---------------------------------------------------------------- fixtures

    private static RunState timed(Map<String, List<Integer>> seconds) {
        RunState state = new RunState();
        state.setCommandSeconds(new LinkedHashMap<>(seconds));
        return state;
    }

    private static CommandResult failure(String command) {
        return new CommandResult(command, 1, "", false, Duration.ZERO, "");
    }

    private static List<String> parts(LastGate gate) {
        return gate.commands().stream().map(GateCommand::state).toList();
    }

    private static void spec(Path wishlist, String id, String parent, String command) throws Exception {
        Files.writeString(wishlist.resolve(id + "-thing.md"), """
                ---
                id: "%s"
                %stitle: Thing %s
                priority: 1
                acceptance:
                  - %s
                ---
                Build it.
                """.formatted(id, parent, id, command));
    }
}
