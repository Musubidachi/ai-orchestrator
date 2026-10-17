package dev.fmangoni.forge.process;

import dev.fmangoni.forge.process.output.ProcessLog;
import dev.fmangoni.forge.process.output.StreamPump;
import dev.fmangoni.forge.process.output.TailBuffer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;

/**
 * Runs an external command, capturing its output and enforcing a wall-clock cap.
 *
 * <p>Prompts are written to <b>stdin</b>, never argv. A brief carries a spec plus a full diff plus
 * captured failure output, a Windows command line is capped near 32k characters, and the JDK
 * applies restrictive quoting rules to arguments passed to {@code .cmd} shims. Both CLIs read their
 * prompt from stdin when it is omitted from the arguments, which sidesteps all three problems.
 */
@UtilityClass
public class CliRunner {

    /** Tail of each stream retained in memory; the full stream always goes to the log file. */
    private final int TAIL_LIMIT = 256 * 1024;

    private final Duration KILL_GRACE = Duration.ofSeconds(15);
    private final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    /** How often the process tree is looked at while a command runs. */
    private final Duration WATCH_INTERVAL = Duration.ofMillis(500);

    /**
     * @param exe     executable or {@code .cmd} shim to run
     * @param args    arguments, excluding the prompt
     * @param cwd     working directory; the agent never receives a path above this
     * @param stdin   written to the process then closed, or {@code null} to close immediately
     * @param timeout wall-clock cap, after which the process tree is destroyed
     * @param logFile transcript destination, appended to
     */
    public ProcResult run(
            Path exe, List<String> args, Path cwd, String stdin, Duration timeout, Path logFile) {
        return run(exe, args, cwd, stdin, timeout, logFile, Set.of());
    }

    /**
     * As above, with some of this process's environment withheld from the child.
     *
     * @param withheld environment variable names the child must not inherit
     */
    public ProcResult run(Path exe, List<String> args, Path cwd, String stdin, Duration timeout,
                          Path logFile, Set<String> withheld) {

        List<String> command = CommandLine.build(exe, args);
        Instant started = Instant.now();

        try (ProcessLog log = ProcessLog.open(logFile)) {
            log.header(command, cwd);

            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            builder.environment().keySet().removeAll(withheld);
            Process proc = builder.start();
            // Taken at once, while its start time can be read: that time is what tells this
            // command's own children from a stranger's.
            ProcessTree tree = ProcessTree.of(proc);
            // Published so a stop request can reach this child; cleared in the finally below.
            ProcessRegistry.register(proc);

            TailBuffer out = new TailBuffer(TAIL_LIMIT);
            TailBuffer err = new TailBuffer(TAIL_LIMIT);
            Thread pumpOut = StreamPump.start(proc.getInputStream(), out, log, "");
            Thread pumpErr = StreamPump.start(proc.getErrorStream(), err, log, "[stderr] ");

            feedStdin(proc, stdin);
            Deadline deadline = Deadline.after(timeout);
            boolean finished = awaitOrKill(proc, tree, deadline);
            drain(pumpOut, pumpErr);

            if (!deadline.slept().isZero()) {
                log.note("the machine was asleep for " + deadline.slept().withNanos(0)
                        + " while this ran; that time was not counted against its " + timeout
                        + " cap");
            }
            int exitCode = finished ? proc.exitValue() : -1;
            Duration elapsed = Duration.between(started, Instant.now());
            log.footer(exitCode, !finished, elapsed);

            return new ProcResult(exitCode, out.value(), err.value(), !finished, elapsed);

        } catch (IOException e) {
            throw new UncheckedIOException("failed to run " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + exe, e);
        } finally {
            ProcessRegistry.unregister();
        }
    }

    private void feedStdin(Process proc, String stdin) throws IOException {
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8))) {
            if (stdin != null) {
                w.write(stdin);
            }
        } catch (IOException e) {
            // A process that exited before reading its input closes the pipe. That is not our
            // failure, and the exit code carries the real signal.
            if (proc.isAlive()) {
                throw e;
            }
        }
    }

    /**
     * Waits for the command, killing it at the cap.
     *
     * <p>Either way, nothing it started is left running. An acceptance command that launches a
     * preview server, or an agent that backgrounds one while checking its work, used to leave it
     * behind whenever the command itself exited normally — holding its port and its file locks
     * into the next gate, which then failed on "address in use", or on a {@code target/} it could
     * not delete. The tree is only reliably visible while its root is alive, so it is noted as the
     * command runs: a child whose parent exited has no link back to this process any more.
     *
     * <p>Through {@link ProcessTree}, never {@code descendants()}. Asked of a command that has
     * exited, that returns whatever now has the command's process id as a parent — which on
     * Windows can be a process that predates it, and for a long time was the editor itself.
     *
     * @return true if the process exited on its own, false if it hit the cap and was killed
     */
    private boolean awaitOrKill(Process proc, ProcessTree tree, Deadline deadline)
            throws InterruptedException {
        Set<ProcessHandle> started = new HashSet<>();
        while (true) {
            started.addAll(tree.members());
            long remaining = deadline.remaining();
            if (remaining <= 0) {
                killTree(proc, tree);
                stopAll(started);
                return false;
            }
            if (proc.waitFor(Math.min(remaining, WATCH_INTERVAL.toNanos()), TimeUnit.NANOSECONDS)) {
                started.addAll(tree.members());
                stopAll(started);
                return true;
            }
        }
    }

    /**
     * Stops whatever is still alive among processes seen under this command.
     *
     * <p>Safe against a recycled process id: a {@code ProcessHandle} carries its process's start
     * time, and an unrelated process that later took the same number is not the one it refers to.
     */
    private void stopAll(Set<ProcessHandle> handles) {
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * Kills descendants before the parent. Both CLIs are npm shims that spawn a node child, so
     * destroying only the {@code cmd.exe} parent would leave the agent running.
     */
    private void killTree(Process proc, ProcessTree tree) throws InterruptedException {
        tree.kill();
        proc.waitFor(KILL_GRACE.toSeconds(), TimeUnit.SECONDS);
    }

    private void drain(Thread... pumps) throws InterruptedException {
        for (Thread pump : pumps) {
            pump.join(DRAIN_GRACE.toMillis());
        }
    }
}
