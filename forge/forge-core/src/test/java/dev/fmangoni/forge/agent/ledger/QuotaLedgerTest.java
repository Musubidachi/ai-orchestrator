package dev.fmangoni.forge.agent.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuotaLedgerTest {

    @TempDir
    Path dir;

    private QuotaLedger ledger() {
        return new QuotaLedger(dir.resolve("quota.json"));
    }

    @Test
    @DisplayName("a limit on one vendor does not block the other")
    void blocksOnlyTheVendorThatReportedIt() {
        QuotaLedger ledger = ledger();
        ledger.record(AgentKind.CODEX, Instant.now().plus(Duration.ofHours(2)), "hit your usage limit");

        assertTrue(ledger.isBlocked(AgentKind.CODEX));
        assertFalse(ledger.isBlocked(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("the block lifts on its own once the stated reset passes")
    void expiresWithoutBeingCleared() {
        QuotaLedger ledger = ledger();
        ledger.record(AgentKind.CLAUDE, Instant.now().minusSeconds(1), "out");

        assertFalse(ledger.isBlocked(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("a limit with no stated reset holds briefly rather than for ever")
    void unknownResetIsNotPermanent() {
        QuotaLedger ledger = ledger();
        ledger.record(AgentKind.CLAUDE, null, "no time given");

        assertTrue(ledger.isBlocked(AgentKind.CLAUDE), "an unknown wait should still slow the queue");
        assertTrue(ledger.blockedUntil(AgentKind.CLAUDE).orElseThrow()
                .isBefore(Instant.now().plus(Duration.ofHours(1))), "and it should not be indefinite");
    }

    @Test
    @DisplayName("a successful call is better evidence than the predicted reset time")
    void clearingUnblocksImmediately() {
        QuotaLedger ledger = ledger();
        ledger.record(AgentKind.CODEX, Instant.now().plus(Duration.ofHours(2)), "out");

        ledger.clear(AgentKind.CODEX);

        assertFalse(ledger.isBlocked(AgentKind.CODEX));
    }

    @Test
    @DisplayName("another process sees the limit, which is the point of writing it down")
    void survivesAcrossInstances() {
        Instant reset = Instant.now().plus(Duration.ofHours(3));
        ledger().record(AgentKind.CODEX, reset, "hit your usage limit");

        assertEquals(reset.getEpochSecond(),
                ledger().blockedUntil(AgentKind.CODEX).orElseThrow().getEpochSecond());
    }

    @Test
    @DisplayName("a corrupt ledger treats vendors as available rather than stopping the run")
    void survivesACorruptFile() throws Exception {
        Files.writeString(dir.resolve("quota.json"), "{ this is not json");

        assertFalse(ledger().isBlocked(AgentKind.CLAUDE));
        assertTrue(ledger().current().isEmpty());
    }

    @Test
    void noRecordMeansAvailable() {
        assertFalse(ledger().isBlocked(AgentKind.CLAUDE));
        assertFalse(ledger().isBlocked(AgentKind.CODEX));
    }
}
