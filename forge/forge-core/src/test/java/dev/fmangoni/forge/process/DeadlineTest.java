package dev.fmangoni.forge.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A command's cap counts the time it could run, not the time the machine was asleep. */
class DeadlineTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private void pass(Duration time) {
        now.addAndGet(time.toNanos());
    }

    /** Checked every half second, as {@link CliRunner} does, for {@code time} in all. */
    private long runFor(Deadline deadline, Duration time) {
        long remaining = deadline.remaining();
        for (long i = 0; i < time.toMillis() / 500; i++) {
            pass(Duration.ofMillis(500));
            remaining = deadline.remaining();
        }
        return remaining;
    }

    @Test
    @DisplayName("a command that runs past its cap is past its cap")
    void awakeTimeCounts() {
        Deadline deadline = new Deadline(Duration.ofMinutes(20), now::get);

        assertTrue(runFor(deadline, Duration.ofMinutes(19)) > 0);
        assertTrue(runFor(deadline, Duration.ofMinutes(2)) <= 0);
        assertEquals(Duration.ZERO, deadline.slept());
    }

    @Test
    @DisplayName("hours asleep in the middle of a gate do not time it out")
    void sleepIsNotCounted() {
        // Project 004: a gate on a twenty-minute cap, six tests in, then the laptop slept until
        // 08:56. The first check after waking found the deadline long gone and killed it.
        Deadline deadline = new Deadline(Duration.ofMinutes(20), now::get);
        runFor(deadline, Duration.ofMinutes(1));

        pass(Duration.ofMinutes(175));

        assertTrue(deadline.remaining() > Duration.ofMinutes(18).toNanos(),
                "the sleep was charged to the command");
        assertEquals(Duration.ofMinutes(175), deadline.slept());
        assertTrue(runFor(deadline, Duration.ofMinutes(20)) <= 0, "and awake time still counts");
    }

    @Test
    @DisplayName("an ordinary slow check is not mistaken for sleep")
    void jitterIsNotSleep() {
        Deadline deadline = new Deadline(Duration.ofMinutes(1), now::get);

        pass(Duration.ofSeconds(5));
        deadline.remaining();

        assertEquals(Duration.ZERO, deadline.slept());
    }
}
