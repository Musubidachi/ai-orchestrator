package dev.fmangoni.forge.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Telling a command's own children from a stranger's.
 *
 * <p>What is being guarded against happened for real. The editor is started detached, so the
 * process that launched it is long gone, and Windows hands that launcher's process id out again.
 * Whichever command drew it — a git call, a gate command, a test — appeared to be the editor's
 * parent, and Forge kills everything under a command once the command has finished. The editor
 * stopped with no log line and no exit code, and took the agent turn it was running with it.
 */
class ProcessTreeTest {

    private static final Instant BEFORE = Instant.parse("2026-10-01T19:00:00Z");
    private static final Instant COMMAND_STARTED = Instant.parse("2026-10-01T20:06:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-01T20:06:05Z");

    @Test
    @DisplayName("what a command started, and what that started in turn, is its tree")
    void findsWhatTheCommandStarted() {
        FakeHandle grandchild = new FakeHandle(30, AFTER.plusSeconds(1));
        FakeHandle child = new FakeHandle(20, AFTER).claims(grandchild);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(child);

        assertEquals(Set.of(child, grandchild), ProcessTree.of(command, COMMAND_STARTED).members());
    }

    @Test
    @DisplayName("a process that was already running when the command began is not its child")
    void aProcessOlderThanTheCommandIsAStranger() {
        FakeHandle editor = new FakeHandle(500, BEFORE);
        FakeHandle own = new FakeHandle(20, AFTER);
        // The command was given the process id the editor's dead launcher used to have.
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(editor).claims(own);

        assertEquals(Set.of(own), ProcessTree.of(command, COMMAND_STARTED).members());
    }

    @Test
    @DisplayName("nor is anything under it, even what it started after the command began")
    void aStrangersChildrenAreStrangers() {
        FakeHandle agentTurn = new FakeHandle(600, AFTER);
        FakeHandle editor = new FakeHandle(500, BEFORE).claims(agentTurn);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(editor);

        assertEquals(Set.of(), ProcessTree.of(command, COMMAND_STARTED).members());
    }

    @Test
    @DisplayName("a process that will not say when it started is left alone")
    void anUnknownStartTimeIsNotTakenOnTrust() {
        FakeHandle silent = new FakeHandle(700, null).claims(new FakeHandle(701, AFTER));
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(silent);

        assertEquals(Set.of(), ProcessTree.of(command, COMMAND_STARTED).members());
    }

    @Test
    @DisplayName("a child that started in the same instant as the command is still its child")
    void theSameInstantCounts() {
        FakeHandle child = new FakeHandle(20, COMMAND_STARTED);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(child);

        assertEquals(Set.of(child), ProcessTree.of(command, COMMAND_STARTED).members());
    }

    @Test
    @DisplayName("killing the tree stops the command and its own, and nobody else")
    void killStopsOnlyItsOwn() {
        FakeHandle agentTurn = new FakeHandle(600, AFTER);
        FakeHandle editor = new FakeHandle(500, BEFORE).claims(agentTurn);
        FakeHandle grandchild = new FakeHandle(30, AFTER.plusSeconds(1));
        FakeHandle child = new FakeHandle(20, AFTER).claims(grandchild);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(editor).claims(child);

        ProcessTree.of(command, COMMAND_STARTED).kill();

        assertTrue(command.killed() && child.killed() && grandchild.killed(),
                "something the command started was left running");
        assertFalse(editor.killed(), "killed a process that only shared a parent id");
        assertFalse(agentTurn.killed(), "killed a stranger's child");
    }

    @Test
    @DisplayName("a process snapshot Windows refuses is asked for again, and the tree is still found")
    void aRefusedSnapshotIsTriedAgain() {
        // 003.4 lost two gates in twelve hours to one refused look among thousands: the JDK's
        // "snapshot not available" went straight up through the gate as "Forge hit an error".
        FakeHandle child = new FakeHandle(20, AFTER);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(child).refuses(ProcessTree.LOOKS - 1);

        assertEquals(Set.of(child), ProcessTree.of(command, COMMAND_STARTED).members());
        assertEquals(ProcessTree.LOOKS, command.looks());
    }

    @Test
    @DisplayName("a snapshot refused every time is a look that saw nothing, not a failed command")
    void aSnapshotRefusedEveryTimeSeesNothing() {
        FakeHandle child = new FakeHandle(20, AFTER);
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).claims(child).refuses(ProcessTree.LOOKS);
        ProcessTree tree = ProcessTree.of(command, COMMAND_STARTED);

        assertEquals(Set.of(), tree.members(), "going without them this time");
        assertEquals(ProcessTree.LOOKS, command.looks(), "gave up after its tries, not before or after");
        assertEquals(Set.of(child), tree.members(), "the next look, as the next interval makes, sees it again");
    }

    @Test
    @DisplayName("a failure that is not a refused snapshot is not taken for one")
    void anyOtherFailureSurfaces() {
        IllegalStateException bug = new IllegalStateException("a real defect");
        FakeHandle command = new FakeHandle(10, COMMAND_STARTED).failsWith(bug);

        assertSame(bug, assertThrows(IllegalStateException.class,
                () -> ProcessTree.of(command, COMMAND_STARTED).members()));
        assertEquals(1, command.looks(), "not retried: only the JDK's bare refusal is");
    }

    @Test
    @DisplayName("a real command's real child is found and killed with it")
    void findsAndKillsARealChild() throws Exception {
        Process shell = shellWithAChild();
        ProcessTree tree = ProcessTree.of(shell);
        try {
            Set<ProcessHandle> members = Set.of();
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (members.isEmpty() && System.nanoTime() < giveUp) {
                members = tree.members();
                Thread.sleep(100);
            }
            assertFalse(members.isEmpty(), "the shell's child was never seen under it");

            tree.kill();

            assertTrue(shell.waitFor(20, TimeUnit.SECONDS), "the shell was not killed");
            for (ProcessHandle member : members) {
                member.onExit().get(20, TimeUnit.SECONDS);
                assertFalse(member.isAlive(), "a child outlived the kill");
            }
        } finally {
            tree.kill();
        }
    }

    /** A shell that has to stay around while its child runs, rather than becoming it. */
    private Process shellWithAChild() throws IOException {
        ProcessBuilder builder = System.getProperty("os.name", "").startsWith("Windows")
                ? new ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul")
                : new ProcessBuilder("sh", "-c", "sleep 60; true");
        return builder.start();
    }
}
