package dev.fmangoni.forge.agent.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.ledger.model.UsageWindow;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.stream.Usage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recording what each window cost, without ever pretending to more certainty than there is.
 *
 * <p>The single most important behaviour here is the one that refuses to answer: until a limit has
 * actually been hit, nothing knows how big a window is, and a made-up figure would have projects
 * planning around headroom that does not exist. Everything else is bookkeeping.
 */
class UsageLedgerTest {

    @TempDir
    Path root;

    private UsageLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new UsageLedger(root.resolve("usage.json"));
    }

    private Usage turn(long total) {
        return new Usage(total, 0, 0, 0);
    }

    @Test
    @DisplayName("consumption accumulates across turns")
    void accumulates() {
        ledger.record(AgentKind.CLAUDE, new Usage(100, 900, 50, 1.5));
        ledger.record(AgentKind.CLAUDE, new Usage(200, 800, 25, 2.5));

        assertEquals(2075, ledger.spentThisWindow(AgentKind.CLAUDE).totalTokens());
        assertEquals(4.0, ledger.spentThisWindow(AgentKind.CLAUDE).costUsd(), 1e-9);
        assertEquals(2, ledger.turnsThisWindow(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("vendors are metered separately, because their windows are separate")
    void vendorsDoNotMix() {
        ledger.record(AgentKind.CLAUDE, turn(1000));
        ledger.record(AgentKind.CODEX, turn(7));

        assertEquals(1000, ledger.spentThisWindow(AgentKind.CLAUDE).totalTokens());
        assertEquals(7, ledger.spentThisWindow(AgentKind.CODEX).totalTokens());
    }

    /** What earlier windows held when the vendor refused, as the dashboard reads it. */
    private List<Long> pastCapacities() {
        UsageWindow window = ledger.snapshot().get(AgentKind.CLAUDE.name());
        return window == null ? List.of() : window.getPastCapacities();
    }

    @Test
    @DisplayName("no window size is known until one has actually run out")
    void noGuessBeforeTheFirstLimit() {
        ledger.record(AgentKind.CLAUDE, turn(5000));

        // The honest answer. A number here would be invented, and everything downstream would
        // treat it as measured.
        assertTrue(pastCapacities().isEmpty());
    }

    @Test
    @DisplayName("running out is what teaches the size of a window")
    void exhaustionTeachesCapacity() {
        ledger.record(AgentKind.CLAUDE, turn(6000));
        ledger.record(AgentKind.CLAUDE, turn(4000));
        ledger.windowExhausted(AgentKind.CLAUDE, null);

        assertEquals(List.of(10_000L), pastCapacities());
        assertEquals(0, ledger.spentThisWindow(AgentKind.CLAUDE).totalTokens(),
                "the window restarts once the vendor has refused");
    }

    @Test
    @DisplayName("every window that ran out is kept, so the page can show the spread")
    void spreadIsKept() {
        ledger.record(AgentKind.CLAUDE, turn(8000));
        ledger.windowExhausted(AgentKind.CLAUDE, null);
        ledger.record(AgentKind.CLAUDE, turn(12_000));
        ledger.windowExhausted(AgentKind.CLAUDE, null);

        assertEquals(List.of(8000L, 12_000L), pastCapacities());
    }

    @Test
    @DisplayName("a refusal before the last one's reset time belongs to the same window")
    void refusalBeforeTheResetIsTheSameWindow() {
        ledger.record(AgentKind.CLAUDE, turn(9_000_000));
        ledger.windowExhausted(AgentKind.CLAUDE, Instant.now().plus(Duration.ofHours(30)));
        // Another model still had quota, so a few turns ran before it was refused too.
        ledger.record(AgentKind.CLAUDE, turn(65_000));
        ledger.windowExhausted(AgentKind.CLAUDE, Instant.now().plus(Duration.ofHours(29)));

        assertEquals(List.of(9_065_000L), pastCapacities(),
                "not a second window that held 65K tokens");
    }

    @Test
    @DisplayName("a refusal after the last one's reset time ends a new window")
    void refusalAfterTheResetIsANewWindow() {
        ledger.record(AgentKind.CLAUDE, turn(9_000_000));
        ledger.windowExhausted(AgentKind.CLAUDE, Instant.now().minus(Duration.ofMinutes(1)));
        ledger.record(AgentKind.CLAUDE, turn(7_000_000));
        ledger.windowExhausted(AgentKind.CLAUDE, null);

        assertEquals(List.of(9_000_000L, 7_000_000L), pastCapacities());
    }

    @Test
    @DisplayName("a refused call adds nothing, since nothing ran")
    void emptyUsageIsIgnored() {
        ledger.record(AgentKind.CLAUDE, Usage.NONE);
        ledger.record(AgentKind.CLAUDE, null);

        assertEquals(0, ledger.turnsThisWindow(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("exhausting a window nothing was spent in teaches nothing")
    void emptyWindowIsNotAnObservation() {
        ledger.windowExhausted(AgentKind.CLAUDE, null);

        // Otherwise the first refusal after a restart would record a capacity of zero, and every
        // project would believe it had no budget at all.
        assertTrue(pastCapacities().isEmpty());
    }
}
