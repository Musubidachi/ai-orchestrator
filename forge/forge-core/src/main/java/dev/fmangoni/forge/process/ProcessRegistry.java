package dev.fmangoni.forge.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.experimental.UtilityClass;

/**
 * Tracks the external process each worker thread is currently waiting on, and makes sure none
 * outlives this JVM.
 *
 * <p>Exists so a job can be stopped from outside. Interrupting the worker thread alone is not
 * enough: {@code waitFor} would return, but the agent CLI it was waiting on would keep running,
 * orphaned, still spending quota and still writing into the project.
 *
 * <p>The same problem arrives from the other direction when Forge itself exits. Windows does not
 * reap a dead process's children, and the job threads are daemons — so the JVM ending takes the
 * threads instantly and leaves their agents behind. Observed for real on this machine: a
 * {@code claude.cmd} launched in the morning still running hours later with a 289 MB
 * {@code claude.exe} under it and no parent alive, plus a Codex {@code node.exe} three days old.
 *
 * <p>Two mechanisms, because one cannot cover everything. A shutdown hook handles Ctrl+C, a
 * terminate signal and normal exit. Nothing in the JVM can run after a forcible kill, a
 * Task-Manager End Task or a power cut — so the pids are also written to disk and swept on the next
 * start. The sweep matches on start time as well as pid, because a pid is reused and killing a
 * stranger that happens to have inherited the number would be far worse than leaving an orphan.
 *
 * <p>The record also names the Forge that wrote it, and nothing in it is swept while that Forge is
 * alive. Two can be running on one root, and the second must not take the first one's work for
 * leftovers.
 */
@UtilityClass
public class ProcessRegistry {

    private final Map<Thread, ProcessTree> RUNNING = new ConcurrentHashMap<>();

    /** Starts the line naming the Forge a record belongs to. Every other line is a child. */
    private final String OWNER = "forge,";

    /** Live children recorded on disk, so a hard kill of this JVM is recoverable next launch. */
    private volatile Path pidFile;

    /**
     * Starts tracking children across restarts, and clears any left by the last one.
     *
     * @return the pids it killed, for the caller to report
     */
    public synchronized List<Long> adoptAndSweep(Path file) {
        List<String> recorded = readLines(file);
        if (writtenByALiveForge(recorded)) {
            // Not orphans: their Forge is running. This one keeps no record of its own rather
            // than write over the other's, and relies on its shutdown hook alone.
            pidFile = null;
            return List.of();
        }
        pidFile = file;
        List<Long> killed = new ArrayList<>();
        for (String line : recorded) {
            if (line.startsWith(OWNER)) {
                continue;
            }
            orphanOf(line).ifPresent(handle -> {
                // Its own start time, read while it is alive: only what it started goes with it.
                ProcessTree.of(handle, handle.info().startInstant().orElseGet(Instant::now)).kill();
                killed.add(handle.pid());
            });
        }
        write(List.of());
        return killed;
    }

    /**
     * Whether the record belongs to another Forge that is running right now.
     *
     * <p>"Left by a previous run" was assumed, never checked, and the assumption is wrong whenever
     * two are started on the same root: a second editor, or a scheduled {@code forge run} beside
     * the editor. The second one read the first one's live children as orphans and killed them
     * before it had got as far as finding the port taken. On 2026-10-01 that was a gate command
     * nineteen passing tests into its run, which was recorded as a failure and sent the project to
     * a repair turn it did not need. So the record now starts with the Forge that wrote it.
     */
    private boolean writtenByALiveForge(List<String> recorded) {
        return recorded.stream()
                .filter(line -> line.startsWith(OWNER))
                .map(line -> orphanOf(line.substring(OWNER.length())))
                .flatMap(Optional::stream)
                .anyMatch(owner -> owner.pid() != ProcessHandle.current().pid());
    }

    /**
     * A still-running process from a previous Forge, or empty.
     *
     * <p>Both halves of the recorded line have to match. The pid says which process; the start time
     * says it is the same one, and not whatever the operating system later gave that number to.
     */
    private Optional<ProcessHandle> orphanOf(String line) {
        String[] parts = line.split(",", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            ProcessHandle handle = ProcessHandle.of(Long.parseLong(parts[0].strip())).orElse(null);
            if (handle == null || !handle.isAlive()) {
                return Optional.empty();
            }
            String started = handle.info().startInstant().map(Object::toString).orElse("");
            return started.equals(parts[1].strip()) ? Optional.of(handle) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    void register(Process process) {
        // As a tree, taken now: once the process has exited its start time cannot be read, and
        // without it there is no telling its children from a stranger's.
        RUNNING.put(Thread.currentThread(), ProcessTree.of(process));
        persist();
    }

    void unregister() {
        RUNNING.remove(Thread.currentThread());
        persist();
    }

    /**
     * Kills whatever the given thread is running, descendants first.
     *
     * <p>The npm shims spawn a node child, so destroying only the {@code cmd.exe} parent would
     * leave the agent alive.
     */
    public void killWhatever(Thread thread) {
        ProcessTree running = RUNNING.get(thread);
        if (running == null) {
            return;
        }
        running.kill();
    }

    /**
     * Kills every child this JVM started. Registered as a shutdown hook.
     *
     * <p>Does not wait for them to finish, and should not. An agent turn runs for tens of minutes,
     * so a shutdown that waited would look like a hang — and waiting buys nothing here, because the
     * pipeline is built for a turn dying mid-flight: {@code turnInFlight} is set before the call,
     * and the next start commits whatever is on disk and sends it to the gate.
     */
    public void killAll() {
        RUNNING.values().forEach(ProcessTree::kill);
        RUNNING.clear();
        write(List.of());
    }

    private void persist() {
        Path file = pidFile;
        if (file == null) {
            return;
        }
        write(RUNNING.values().stream()
                .map(ProcessTree::root)
                .filter(ProcessHandle::isAlive)
                .map(ProcessRegistry::recordOf)
                .toList());
    }

    /** A process as one line: its id, and the start time that says it is still the same one. */
    private String recordOf(ProcessHandle handle) {
        return handle.pid() + "," + handle.info().startInstant().map(Object::toString).orElse("");
    }

    private void write(List<String> lines) {
        Path file = pidFile;
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            List<String> record = new ArrayList<>(List.of(OWNER + recordOf(ProcessHandle.current())));
            record.addAll(lines);
            Files.writeString(file, String.join("\n", record), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Bookkeeping. Losing it costs a stray process next time, not this run.
            System.err.println("could not record child pids: " + e.getMessage());
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .filter(line -> !line.isBlank()).toList()
                    : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
