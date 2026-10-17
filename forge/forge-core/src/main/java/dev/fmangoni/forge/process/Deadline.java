package dev.fmangoni.forge.process;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A command's time cap that does not count time the machine spent asleep.
 *
 * <p>Checked every half second by {@link CliRunner}. A gap between two checks far longer than
 * that means nothing in this process ran — the machine was suspended — and the command's own
 * processes did not run either, so none of it is charged against the cap.
 *
 * <p>Project 004's gate was recorded as timing out after 2h56m against a twenty-minute cap: six
 * tests finished, then silence until 08:56, when Windows logged the machine's session resuming.
 * The monotonic clock had kept counting through the sleep, so the first check after waking found
 * the deadline long gone and killed a suite that had been running for about a minute of real
 * time. That cost a repair round, and an agent turn spent diagnosing a deadlock that did not exist;
 * the same suite has since passed hundreds of times unchanged.
 */
final class Deadline {

    /** A gap between checks this long is not scheduling noise. */
    private static final long ASLEEP_AFTER = Duration.ofSeconds(30).toNanos();

    private final LongSupplier clock;
    private long deadline;
    private long lastLook;
    private long slept;

    Deadline(Duration timeout, LongSupplier clock) {
        this.clock = clock;
        this.lastLook = clock.getAsLong();
        this.deadline = lastLook + timeout.toNanos();
    }

    static Deadline after(Duration timeout) {
        return new Deadline(timeout, System::nanoTime);
    }

    /** Nanoseconds left, having moved the deadline past any stretch the machine was asleep. */
    long remaining() {
        long now = clock.getAsLong();
        long gap = now - lastLook;
        if (gap > ASLEEP_AFTER) {
            deadline += gap;
            slept += gap;
        }
        lastLook = now;
        return deadline - now;
    }

    /** How much time was discounted, for the transcript. */
    Duration slept() {
        return Duration.ofNanos(slept);
    }
}
