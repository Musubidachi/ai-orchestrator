package dev.fmangoni.forge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeBudgetTest {

    @Test
    @DisplayName("the agent is told less than the hard cap, so it can still hand over")
    void reservesTimeForTheHandoff() {
        Duration cap = Duration.ofMinutes(30);

        assertTrue(TimeBudget.working(cap).compareTo(cap) < 0);
        assertEquals(30, TimeBudget.working(cap).plus(TimeBudget.reserve(cap)).toMinutes());
    }

    @Test
    void reserveGrowsWithTheCap() {
        assertTrue(TimeBudget.reserve(Duration.ofHours(2))
                .compareTo(TimeBudget.reserve(Duration.ofMinutes(30))) > 0);
    }

    @Test
    @DisplayName("a short cap keeps a usable floor rather than a token minute")
    void keepsAMinimumReserve() {
        assertEquals(4, TimeBudget.reserve(Duration.ofMinutes(10)).toMinutes());
    }

    @Test
    @DisplayName("a cap too small to split is spent working rather than going negative")
    void degradesGracefullyOnATinyCap() {
        Duration tiny = Duration.ofMinutes(3);

        assertEquals(tiny, TimeBudget.working(tiny));
    }
}
