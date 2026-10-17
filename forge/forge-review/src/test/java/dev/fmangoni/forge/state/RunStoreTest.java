package dev.fmangoni.forge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.fmangoni.forge.project.Phase;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a snapshot another process has moved past is not advanced over its work")
    void aStaleSnapshotLosesToTheFile() throws Exception {
        Path file = root.resolve("state.json");
        RunState saved = RunState.builder().id("003").phase(Phase.GATING).sessions(16)
                .createdAt(Instant.now()).build();
        RunStore.save(file, saved);
        RunState snapshot = RunStore.load(file).orElseThrow();
        snapshot.setTurnInFlight(true);

        // The editor finishes a turn and lets go of the lock before the scheduled run takes it.
        Thread.sleep(5);
        RunState elsewhere = RunStore.load(file).orElseThrow();
        elsewhere.setSessions(17);
        elsewhere.setPhase(Phase.DONE);
        RunStore.save(file, elsewhere);

        RunState used = RunStore.latest(file, snapshot);

        assertEquals(Phase.DONE, used.getPhase());
        assertEquals(17, used.getSessions());
    }

    @Test
    @DisplayName("a snapshot nobody has written past is kept, overrides and all")
    void anUnchangedSnapshotIsKept() {
        Path file = root.resolve("state.json");
        RunState state = RunState.builder().id("003").phase(Phase.SUSPENDED)
                .createdAt(Instant.now()).build();
        RunStore.save(file, state);
        RunState snapshot = RunStore.load(file).orElseThrow();

        assertSame(snapshot, RunStore.latest(file, snapshot));
        RunState fresh = RunState.builder().id("004").build();
        assertSame(fresh, RunStore.latest(root.resolve("never-written.json"), fresh),
                "a project with no state file yet runs from the one just created");
    }
}
