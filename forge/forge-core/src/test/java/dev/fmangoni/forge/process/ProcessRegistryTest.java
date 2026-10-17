package dev.fmangoni.forge.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Making sure no agent outlives the Forge that started it.
 *
 * <p>Windows does not reap a dead process's children, and Forge's job threads are daemons — so an
 * exiting JVM leaves its agents running. This was not hypothetical: a {@code claude.cmd} launched
 * in the morning was still alive hours later, holding 289 MB, with no parent process at all.
 *
 * <p>The tests that matter most here are the ones about <em>not</em> killing things. The sweep runs
 * at startup against pids recorded by a previous run, and a pid is reused — so it has to refuse
 * anything whose start time does not match, or one day it will kill a stranger that inherited the
 * number. A leftover orphan is a nuisance; killing an unrelated process is a different category.
 */
class ProcessRegistryTest {

    @TempDir
    Path root;

    /** A real process that will sit still until told otherwise. */
    private Process sleeper() throws IOException {
        ProcessBuilder builder = System.getProperty("os.name", "").startsWith("Windows")
                ? new ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul")
                : new ProcessBuilder("sh", "-c", "sleep 60");
        return builder.start();
    }

    private String recordFor(ProcessHandle handle) {
        return handle.pid() + ","
                + handle.info().startInstant().map(Object::toString).orElse("");
    }

    @Test
    @DisplayName("a process recorded by a previous run is killed at startup")
    void sweepsOrphansFromALastRun() throws Exception {
        Process orphan = sleeper();
        Path pids = root.resolve("children.pids");
        Files.writeString(pids, recordFor(orphan.toHandle()), StandardCharsets.UTF_8);

        List<Long> killed = ProcessRegistry.adoptAndSweep(pids);

        assertEquals(List.of(orphan.pid()), killed);
        assertTrue(orphan.waitFor(20, TimeUnit.SECONDS), "the orphan was not actually killed");
        assertFalse(orphan.isAlive());
    }

    @Test
    @DisplayName("a pid that has been reused is left completely alone")
    void refusesAPidWhoseStartTimeDiffers() throws Exception {
        Process bystander = sleeper();
        ProcessTree itsOwn = ProcessTree.of(bystander);
        Path pids = root.resolve("children.pids");
        // Same pid, a start time from before it existed: exactly what pid reuse looks like.
        Files.writeString(pids, bystander.pid() + ",2020-01-01T00:00:00Z",
                StandardCharsets.UTF_8);

        try {
            assertEquals(List.of(), ProcessRegistry.adoptAndSweep(pids));
            assertTrue(bystander.isAlive(), "killed a process that merely shared a pid");
        } finally {
            itsOwn.kill();
        }
    }

    @Test
    @DisplayName("the record is cleared once swept, so nothing is hunted twice")
    void clearsTheRecord() throws Exception {
        Path pids = root.resolve("children.pids");
        Files.writeString(pids, "999999,2020-01-01T00:00:00Z", StandardCharsets.UTF_8);

        ProcessRegistry.adoptAndSweep(pids);

        assertEquals(List.of("forge," + recordFor(ProcessHandle.current())),
                Files.readAllLines(pids, StandardCharsets.UTF_8),
                "only the line naming this Forge should be left");
    }

    @Test
    @DisplayName("while the Forge that wrote the record is running, nothing in it is an orphan")
    void leavesALiveForgesChildrenAlone() throws Exception {
        // What a second copy finds when it is started beside a working one: the first one's gate
        // command or agent turn, recorded and alive.
        Process otherForge = sleeper();
        Process itsCommand = sleeper();
        ProcessTree forgeTree = ProcessTree.of(otherForge);
        ProcessTree commandTree = ProcessTree.of(itsCommand);
        Path pids = root.resolve("children.pids");
        String record = "forge," + recordFor(otherForge.toHandle()) + "\n" + recordFor(itsCommand.toHandle());
        Files.writeString(pids, record, StandardCharsets.UTF_8);

        try {
            assertEquals(List.of(), ProcessRegistry.adoptAndSweep(pids));
            assertTrue(itsCommand.isAlive(), "killed a command belonging to a Forge that is still running");
            assertEquals(record, Files.readString(pids, StandardCharsets.UTF_8),
                    "wrote over another Forge's record");
        } finally {
            commandTree.kill();
            forgeTree.kill();
        }
    }

    @Test
    @DisplayName("once that Forge has gone, what it left running is swept as before")
    void sweepsWhatADeadForgeLeft() throws Exception {
        Process orphan = sleeper();
        Path pids = root.resolve("children.pids");
        Files.writeString(pids, "forge,999999,2020-01-01T00:00:00Z\n" + recordFor(orphan.toHandle()),
                StandardCharsets.UTF_8);

        List<Long> killed = ProcessRegistry.adoptAndSweep(pids);

        assertEquals(List.of(orphan.pid()), killed);
        assertTrue(orphan.waitFor(20, TimeUnit.SECONDS), "the orphan was not actually killed");
    }

    @Test
    @DisplayName("a process that merely has a dead Forge's old id does not protect its orphans")
    void aReusedForgeIdIsNotALiveForge() throws Exception {
        Process stranger = sleeper();
        Process orphan = sleeper();
        ProcessTree strangerTree = ProcessTree.of(stranger);
        Path pids = root.resolve("children.pids");
        // The dead Forge's pid now belongs to something else: same number, a different start time.
        Files.writeString(pids, "forge," + stranger.pid() + ",2020-01-01T00:00:00Z\n"
                + recordFor(orphan.toHandle()), StandardCharsets.UTF_8);

        try {
            assertEquals(List.of(orphan.pid()), ProcessRegistry.adoptAndSweep(pids));
            assertTrue(stranger.isAlive(), "killed the process that inherited the Forge's id");
        } finally {
            strangerTree.kill();
        }
    }

    @Test
    @DisplayName("no record, a dead pid, or a malformed line: nothing happens and nothing throws")
    void toleratesRubbish() throws Exception {
        assertEquals(List.of(), ProcessRegistry.adoptAndSweep(root.resolve("absent.pids")));

        Path pids = root.resolve("children.pids");
        Files.writeString(pids, "not-a-pid\n\n12,\n999999,2020-01-01T00:00:00Z\n",
                StandardCharsets.UTF_8);

        assertEquals(List.of(), ProcessRegistry.adoptAndSweep(pids));
    }

    @Test
    @DisplayName("killAll reaches the whole tree, not just the shim that started it")
    void killAllReachesDescendants() throws Exception {
        ProcessRegistry.adoptAndSweep(root.resolve("children.pids"));
        Process outer = sleeper();

        // register() is package-private and keyed by the calling thread, which is this one.
        registerOnThisThread(outer);
        ProcessRegistry.killAll();

        assertTrue(outer.waitFor(20, TimeUnit.SECONDS));
        assertFalse(outer.isAlive());
    }

    private void registerOnThisThread(Process process) throws Exception {
        var method = ProcessRegistry.class.getDeclaredMethod("register", Process.class);
        method.setAccessible(true);
        method.invoke(null, process);
    }
}
