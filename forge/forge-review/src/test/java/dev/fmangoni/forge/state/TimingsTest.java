package dev.fmangoni.forge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning a number the gate already had into a performance signal.
 *
 * <p>The tests that matter here are the ones about staying quiet. This reports to a person, and a
 * section that cries regression every run because a two-second command took six is a section nobody
 * reads by the third report — at which point the one real slowdown goes past unnoticed too.
 */
class TimingsTest {

    private RunState state = new RunState();

    private void gateRun(String command, int seconds) {
        Timings.record(state, List.of(new CommandResult(
                command, 0, "", false, Duration.ofSeconds(seconds), null)));
    }

    private TimingTrend only() {
        List<TimingTrend> trends = Timings.of(state);
        assertEquals(1, trends.size());
        return trends.getFirst();
    }

    @Test
    @DisplayName("the first run establishes a baseline and claims nothing")
    void firstRunIsNotARegression() {
        gateRun("mvn -q verify", 90);

        TimingTrend trend = only();
        assertEquals(90, trend.latest());
        assertEquals(1, trend.runs());
        assertFalse(trend.slowerThanUsual(), "there is nothing yet to be slower than");
    }

    @Test
    @DisplayName("a command that doubles against a settled history is reported")
    void realSlowdownIsReported() {
        gateRun("mvn -q verify", 60);
        gateRun("mvn -q verify", 62);
        gateRun("mvn -q verify", 58);
        gateRun("mvn -q verify", 140);

        TimingTrend trend = only();
        assertEquals(140, trend.latest());
        assertEquals(60, trend.usual());
        assertTrue(trend.slowerThanUsual());
    }

    @Test
    @DisplayName("a fast command tripling is noise, not a regression")
    void shortCommandsAreLeftAlone() {
        gateRun("npm test", 1);
        gateRun("npm test", 1);
        gateRun("npm test", 3);

        // Process start-up, a cold cache, a scanner waking up. Without a floor every quick command
        // reports constantly and the section stops being read.
        assertFalse(only().slowerThanUsual());
    }

    @Test
    @DisplayName("seconds of jitter on a short suite is not a slowdown, however large the ratio")
    void smallAbsoluteChangesAreLeftAlone() {
        // Every card on this machine said "slower than usual" under the old 5-second floor; these
        // are two of the real figures. A warning on every card is read on none.
        gateRun("mvn -q test", 3);
        gateRun("mvn -q test", 3);
        gateRun("mvn -q test", 6);
        assertFalse(only().slowerThanUsual(), "6s against 3s");

        state.setCommandSeconds(new LinkedHashMap<>());
        gateRun("pip install -e .", 8);
        gateRun("pip install -e .", 8);
        gateRun("pip install -e .", 14);
        assertFalse(only().slowerThanUsual(), "14s against 8s");

        state.setCommandSeconds(new LinkedHashMap<>());
        gateRun("npm run test:e2e", 51);
        gateRun("npm run test:e2e", 51);
        gateRun("npm run test:e2e", 94);
        assertTrue(only().slowerThanUsual(), "94s against 51s is worth a look");
    }

    @Test
    @DisplayName("one slow run does not redefine what usual means")
    void medianResistsASingleOutlier() {
        gateRun("mvn -q verify", 60);
        gateRun("mvn -q verify", 600);
        gateRun("mvn -q verify", 62);
        gateRun("mvn -q verify", 64);

        TimingTrend trend = only();
        assertEquals(62, trend.usual(), "the median ignores the outlier a mean would absorb");
        assertFalse(trend.slowerThanUsual());
    }

    @Test
    @DisplayName("getting faster is never a complaint")
    void speedupIsNotReported() {
        gateRun("mvn -q verify", 200);
        gateRun("mvn -q verify", 190);
        gateRun("mvn -q verify", 20);

        assertFalse(only().slowerThanUsual());
    }

    @Test
    @DisplayName("each command is tracked against its own history")
    void commandsAreSeparate() {
        gateRun("mvn -q test", 10);
        gateRun("npm run e2e", 300);

        assertEquals(2, Timings.of(state).size());
        assertEquals(List.of(10), state.getCommandSeconds().get("mvn -q test"));
        assertEquals(List.of(300), state.getCommandSeconds().get("npm run e2e"));
    }

    @Test
    @DisplayName("history is bounded, so a long-running project does not grow a tail forever")
    void historyIsBounded() {
        for (int run = 0; run < 40; run++) {
            gateRun("mvn -q verify", 30);
        }

        assertEquals(10, state.getCommandSeconds().get("mvn -q verify").size());
    }

    @Test
    @DisplayName("a project that has never gated reports nothing at all")
    void noHistoryIsNoSection() {
        assertTrue(Timings.of(new RunState()).isEmpty());
    }
}
