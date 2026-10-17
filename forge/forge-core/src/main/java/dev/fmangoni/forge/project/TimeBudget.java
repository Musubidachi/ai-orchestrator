package dev.fmangoni.forge.project;

import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * Splits an agent turn's wall clock into working time and a reserve for handing over.
 *
 * <p>The reserve exists because a process killed at its cap never gets to say anything. An agent
 * told it has the full thirty minutes will still be mid-edit at thirty minutes, and Forge will
 * destroy it holding everything it had learned. Telling it a smaller number buys back the few
 * minutes needed to write down what it finished, what it did not, and what the next session should
 * pick up.
 */
@UtilityClass
public class TimeBudget {

    /** Enough to summarise and commit, not enough to start anything new. */
    private final Duration MIN_RESERVE = Duration.ofMinutes(4);

    private final double RESERVE_FRACTION = 0.15;

    /** What the agent is told it has. */
    public Duration working(Duration cap) {
        Duration reserve = reserve(cap);
        Duration working = cap.minus(reserve);
        // On a very short cap there is no sensible split; give it everything and accept that a
        // handoff may not arrive.
        return working.isPositive() ? working : cap;
    }

    public Duration reserve(Duration cap) {
        Duration proportional = Duration.ofSeconds((long) (cap.toSeconds() * RESERVE_FRACTION));
        return proportional.compareTo(MIN_RESERVE) > 0 ? proportional : MIN_RESERVE;
    }
}
