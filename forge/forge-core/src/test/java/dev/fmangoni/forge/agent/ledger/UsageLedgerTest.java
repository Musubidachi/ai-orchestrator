package dev.fmangoni.forge.agent.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.stream.Usage;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Turning measurements into a budget, without ever pretending to more certainty than there is.
 *
 * <p>The single most important behaviour here is the one that refuses to answer: until a limit has
 * actually been hit, nothing knows how big a window is, and a made-up figure would have projects
 * planning around headroom that does not exist. Everything else is arithmetic.
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

    @Test
    @DisplayName("remaining budget is unknown until a window has actually run out")
    void noGuessBeforeTheFirstLimit() {
        ledger.record(AgentKind.CLAUDE, turn(5000));

        // The honest answer. A number here would be invented, and everything downstream would
        // treat it as measured.
        assertTrue(ledger.learnedCapacity(AgentKind.CLAUDE).isEmpty());
        assertTrue(ledger.estimatedRemaining(AgentKind.CLAUDE).isEmpty());
    }

    @Test
    @DisplayName("running out is what teaches the size of a window")
    void exhaustionTeachesCapacity() {
        ledger.record(AgentKind.CLAUDE, turn(6000));
        ledger.record(AgentKind.CLAUDE, turn(4000));
        ledger.windowExhausted(AgentKind.CLAUDE);

        assertEquals(10_000, ledger.learnedCapacity(AgentKind.CLAUDE).orElseThrow());
        assertEquals(0, ledger.spentThisWindow(AgentKind.CLAUDE).totalTokens(),
                "the window restarts once the vendor has refused");
        assertEquals(10_000, ledger.estimatedRemaining(AgentKind.CLAUDE).orElseThrow());
    }

    @Test
    @DisplayName("capacity is averaged over past windows rather than taken from the last")
    void capacityIsAveraged() {
        ledger.record(AgentKind.CLAUDE, turn(8000));
        ledger.windowExhausted(AgentKind.CLAUDE);
        ledger.record(AgentKind.CLAUDE, turn(12_000));
        ledger.windowExhausted(AgentKind.CLAUDE);

        // One short window — an interrupted evening, a vendor hiccup — should not redefine what a
        // full one holds.
        assertEquals(10_000, ledger.learnedCapacity(AgentKind.CLAUDE).orElseThrow());
    }

    @Test
    @DisplayName("what is left falls as the window is spent")
    void remainingFalls() {
        ledger.record(AgentKind.CLAUDE, turn(10_000));
        ledger.windowExhausted(AgentKind.CLAUDE);

        ledger.record(AgentKind.CLAUDE, turn(3000));
        assertEquals(7000, ledger.estimatedRemaining(AgentKind.CLAUDE).orElseThrow());

        ledger.record(AgentKind.CLAUDE, turn(9000));
        assertEquals(0, ledger.estimatedRemaining(AgentKind.CLAUDE).orElseThrow(),
                "overrunning the estimate reports nothing left, not a negative budget");
    }

    @Test
    @DisplayName("the average turn is what a budget is spent in units of")
    void averageTurn() {
        ledger.record(AgentKind.CLAUDE, turn(3000));
        ledger.record(AgentKind.CLAUDE, turn(5000));

        assertEquals(4000, ledger.averageTurn(AgentKind.CLAUDE).orElseThrow());
        assertTrue(ledger.averageTurn(AgentKind.CODEX).isEmpty(), "nothing has run on Codex");
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
        ledger.windowExhausted(AgentKind.CLAUDE);

        // Otherwise the first refusal after a restart would record a capacity of zero, and every
        // project would believe it had no budget at all.
        assertTrue(ledger.learnedCapacity(AgentKind.CLAUDE).isEmpty());
    }
}
