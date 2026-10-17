package dev.fmangoni.forge.process;

import java.time.Duration;

/**
 * Outcome of a single external process invocation.
 *
 * <p>{@code stdout} and {@code stderr} hold only the tail of the stream; the complete output is
 * always on disk in the run's {@code logs/} directory. Agent transcripts routinely run to
 * megabytes, and none of the decisions Forge makes need more than the tail.
 */
public record ProcResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        Duration elapsed) {

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }

    /** Both streams together, for pattern matching where the CLI's choice of stream is inconsistent. */
    public String combined() {
        return stdout + "\n" + stderr;
    }
}
