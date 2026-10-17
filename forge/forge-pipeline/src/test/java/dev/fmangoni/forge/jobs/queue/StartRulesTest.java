package dev.fmangoni.forge.jobs.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a vendor's reset time is said, which is what a person plans their evening around. */
class StartRulesTest {

    @Test
    @DisplayName("a reset later today is said as a time, and one days away with its day")
    void aResetDaysAwayNamesItsDay() {
        // Codex's weekly limit: "out of quota until 5:39 PM", shown on the Sunday for a reset on the
        // Friday, read as later that afternoon.
        ZoneId zone = ZoneId.systemDefault();
        Instant laterToday = LocalDate.now(zone).atTime(LocalTime.of(23, 58)).atZone(zone).toInstant();
        Instant daysAway = Instant.now().plus(4, ChronoUnit.DAYS);

        String today = StartRules.local(laterToday);
        String away = StartRules.local(daysAway);

        assertTrue(today.matches("\\d{1,2}:\\d{2} [AP]M"), today);
        assertTrue(away.matches("[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{1,2}, \\d{1,2}:\\d{2} [AP]M"), away);
        assertFalse(away.equals(today));
    }
}
