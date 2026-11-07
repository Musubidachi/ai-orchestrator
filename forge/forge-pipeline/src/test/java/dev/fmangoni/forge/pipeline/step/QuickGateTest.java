package dev.fmangoni.forge.pipeline.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which commands a quick gate runs: those before the first one that usually takes long. */
class QuickGateTest {

    private static final String BUILD = "mvn -q verify";
    private static final String SUITE = "npx playwright test";

    @Test
    @DisplayName("the commands before the first slow one are the quick ones, in the spec's order")
    void upToTheFirstSlowOne() {
        assertEquals(1, QuickGate.leadingQuick(List.of(BUILD, SUITE),
                Map.of(BUILD, List.of(40), SUITE, List.of(700))));
        assertEquals(0, QuickGate.leadingQuick(List.of(SUITE, BUILD),
                Map.of(BUILD, List.of(40), SUITE, List.of(700))), "nothing after a slow command runs");
        assertEquals(2, QuickGate.leadingQuick(List.of(BUILD, SUITE),
                Map.of(BUILD, List.of(40), SUITE, List.of(30))));
    }

    @Test
    @DisplayName("nothing is quick until every command has been timed")
    void untimedCommandsMayBeTheSlowOnes() {
        assertEquals(0, QuickGate.leadingQuick(List.of(BUILD, SUITE), null));
        assertEquals(0, QuickGate.leadingQuick(List.of(BUILD, SUITE), Map.of(BUILD, List.of(40))));
        assertEquals(0, QuickGate.leadingQuick(List.of(BUILD, SUITE),
                Map.of(BUILD, List.of(40), SUITE, List.of())));
    }

    @Test
    @DisplayName("a command's usual time is the middle of its runs, so one odd run changes nothing")
    void oneOddRunChangesNothing() {
        assertEquals(1, QuickGate.leadingQuick(List.of(BUILD, SUITE),
                Map.of(BUILD, List.of(40, 900, 40), SUITE, List.of(700))), "one slow build is not a slow build");
        assertEquals(1, QuickGate.leadingQuick(List.of(BUILD, SUITE),
                Map.of(BUILD, List.of(40), SUITE, List.of(700, 5, 700))), "a suite that failed fast once is still slow");
    }

    @Test
    @DisplayName("a command that usually takes SLOW_SECONDS is slow")
    void theLine() {
        assertEquals(0, QuickGate.leadingQuick(List.of(BUILD), Map.of(BUILD, List.of(QuickGate.SLOW_SECONDS))));
        assertEquals(1, QuickGate.leadingQuick(List.of(BUILD), Map.of(BUILD, List.of(QuickGate.SLOW_SECONDS - 1))));
    }
}
