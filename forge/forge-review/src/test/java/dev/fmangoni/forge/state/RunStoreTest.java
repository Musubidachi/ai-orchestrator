package dev.fmangoni.forge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.fmangoni.forge.project.Phase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
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

    @Test
    @DisplayName("an unreadable state file is reported once, not on every look, and again once it changes")
    void anUnreadableFileIsReportedOnce() throws Exception {
        // The dashboard surveys every few seconds: one damaged file printed the same two lines
        // hundreds of times a minute, which is what a person scrolling the log saw.
        Path broken = root.resolve("state.json");
        Files.writeString(broken, "{\"id\":\"001\",\"phase\":\"GATI");
        PrintStream original = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            for (int i = 0; i < 5; i++) {
                assertEquals(Optional.empty(), RunStore.survey(broken));
            }
            assertEquals(1, lines(printed), "said once: " + printed);

            Files.writeString(broken, "{\"id\":\"001\",\"pha");
            RunStore.survey(broken);
            assertEquals(2, lines(printed), "a different breakage is news: " + printed);

            RunStore.save(broken, RunState.builder().id("001").build());
            RunStore.survey(broken);
            Files.writeString(broken, "{\"id\":\"001\",\"pha");
            RunStore.survey(broken);
            assertEquals(3, lines(printed), "broken again after being mended is news too: " + printed);
        } finally {
            System.setErr(original);
        }
    }

    private static long lines(ByteArrayOutputStream printed) {
        return printed.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("cannot read run state")).count();
    }
}
