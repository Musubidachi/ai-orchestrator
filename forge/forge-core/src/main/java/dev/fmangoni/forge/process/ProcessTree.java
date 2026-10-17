package dev.fmangoni.forge.process;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The processes a command really started, found without taking a process id's word for it.
 *
 * <p>{@code ProcessHandle.descendants()} matches children by parent id. While the parent is alive
 * the JDK also checks that a child started after it; once the parent has exited it has no start
 * time to check against, and matches on the number alone. Windows reuses that number. So a command
 * that happened to be given the id of some long-dead process "adopted" whatever that process had
 * left running, and everything under it — and Forge, which asks for a command's descendants after
 * it exits precisely so that it can kill them, killed those instead.
 *
 * <p>What it killed was itself. The editor is started detached, so its parent is always a dead
 * launcher. A git call or a gate command that drew the launcher's old id took the editor down, and
 * the agent turn it was running with it: no log line, no exit code, nothing in the event log. It
 * was reported as stopping "on its own one night" long before it was understood, and a test run in
 * the same checkout could do it too. Reproduced deliberately on 2026-10-01 — an unrelated orphan
 * was reported as a descendant after about six thousand spawns.
 *
 * <p>So the tree is walked a level at a time, and a process is only somebody's child if it started
 * no earlier than that somebody did. One whose start time cannot be read is left alone: not killing
 * a leftover is recoverable, and killing a stranger is not.
 */
public final class ProcessTree {

    private final ProcessHandle root;
    private final Instant started;

    private ProcessTree(ProcessHandle root, Instant started) {
        this.root = root;
        this.started = started;
    }

    /**
     * The tree under a process just started.
     *
     * <p>Called while it is alive, which is when its start time can still be read. If it cannot,
     * now is used: later than the truth, so at worst a child from the first instant is missed.
     */
    public static ProcessTree of(Process process) {
        return new ProcessTree(process.toHandle(), process.info().startInstant().orElseGet(Instant::now));
    }

    public static ProcessTree of(ProcessHandle root, Instant started) {
        return new ProcessTree(root, started);
    }

    public ProcessHandle root() {
        return root;
    }

    public Instant started() {
        return started;
    }

    /** Everything under the root right now that the root, or something it started, started. */
    public Set<ProcessHandle> members() {
        Set<ProcessHandle> found = new LinkedHashSet<>();
        collect(root, started, found);
        return found;
    }

    /** Kills the members, then the root. */
    public void kill() {
        members().forEach(ProcessHandle::destroyForcibly);
        root.destroyForcibly();
    }

    private static void collect(ProcessHandle parent, Instant parentStarted, Set<ProcessHandle> found) {
        parent.children().forEach(child -> {
            Instant childStarted = child.info().startInstant().orElse(null);
            if (childStarted == null || childStarted.isBefore(parentStarted)) {
                return;
            }
            if (found.add(child)) {
                collect(child, childStarted, found);
            }
        });
    }
}
