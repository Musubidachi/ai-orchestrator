package dev.fmangoni.forge.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seam between observing a limit and the dashboard knowing about it.
 *
 * <p>Everything downstream of this — the quota banner, the refusal to queue, the countdown on a
 * suspended card — is driven by whether one call to {@code quota.record} happens here. If it does
 * not, nothing anywhere reports an error; the dashboard simply shows a queue that never advances
 * and no reason why.
 *
 * <p>Also covers the semaphore, which is the thing standing between an unattended overnight run and
 * a subscription window drained by parallel sessions.
 */
class ThrottledAgentTest {

    @TempDir
    Path dir;

    private QuotaLedger quota;

    @BeforeEach
    void setUp() {
        quota = new QuotaLedger(dir.resolve("quota.json"));
    }

    private static AgentResult resultOf(AgentOutcome outcome, String message, Instant retryAfter) {
        return new AgentResult(outcome, message,
                new ProcResult(0, "", "", false, Duration.ofSeconds(1)), retryAfter);
    }

    /** An agent that returns a scripted outcome without going near a CLI. */
    private record StubAgent(AgentKind kind, AgentResult result) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            return result;
        }
    }

    private Agent wrapping(AgentResult result) {
        return ThrottledAgent.wrapping(new StubAgent(AgentKind.CLAUDE, result), 1, quota);
    }

    private AgentTask anyTask() {
        return AgentTask.writable(dir, "do the thing", Duration.ofMinutes(1), dir);
    }

    @Test
    @DisplayName("a rate-limited call is written to the ledger with its reset time")
    void recordsRateLimits() {
        Instant reset = Instant.now().plus(2, ChronoUnit.HOURS);

        wrapping(resultOf(AgentOutcome.RATE_LIMITED, "you have hit your usage limit", reset))
                .run(anyTask());

        assertTrue(quota.isBlocked(AgentKind.CLAUDE));
        assertEquals(reset, quota.blockedUntil(AgentKind.CLAUDE).orElseThrow());
        assertTrue(quota.current().get("CLAUDE").getMessage().contains("usage limit"),
                "the vendor's own words are what the dashboard shows");
    }

    @Test
    @DisplayName("a limit with no stated reset still blocks, briefly")
    void recordsLimitsWithoutAResetTime() {
        wrapping(resultOf(AgentOutcome.RATE_LIMITED, "slow down", null)).run(anyTask());

        // Knowing a vendor is out matters even when the wait is unknown; the alternative is
        // treating it as available and hammering it.
        assertTrue(quota.isBlocked(AgentKind.CLAUDE));
        assertTrue(quota.blockedUntil(AgentKind.CLAUDE).orElseThrow()
                .isBefore(Instant.now().plus(20, ChronoUnit.MINUTES)),
                "an unknown wait should slow the queue, not stop it for good");
    }

    @Test
    @DisplayName("a completed call clears the block, because it is proof the window reopened")
    void completionClearsTheLedger() {
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "limit");

        wrapping(resultOf(AgentOutcome.COMPLETED, "done", null)).run(anyTask());

        assertFalse(quota.isBlocked(AgentKind.CLAUDE),
                "a call that worked is better evidence than the reset time predicted earlier");
    }

    @Test
    @DisplayName("an ordinary failure neither records nor clears a limit")
    void failuresLeaveTheLedgerAlone() {
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "limit");

        wrapping(resultOf(AgentOutcome.FAILED, "compile error", null)).run(anyTask());

        assertTrue(quota.isBlocked(AgentKind.CLAUDE),
                "a failing build says nothing about whether the vendor is available");
    }

    @Test
    @DisplayName("one vendor's limit does not touch the other's record")
    void vendorsAreRecordedSeparately() {
        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "codex limit");

        wrapping(resultOf(AgentOutcome.COMPLETED, "done", null)).run(anyTask());

        assertFalse(quota.isBlocked(AgentKind.CLAUDE));
        assertTrue(quota.isBlocked(AgentKind.CODEX), "Claude succeeding says nothing about Codex");
    }

    @Test
    @DisplayName("only one call reaches a vendor at a time")
    void concurrencyIsCappedPerVendor() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(4);

        Agent counting = ThrottledAgent.wrapping(new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return resultOf(AgentOutcome.COMPLETED, "done", null);
            }
        }, 1, quota);

        for (int i = 0; i < 4; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    counting.run(anyTask());
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "the throttled calls did not finish");

        // Fanning out parallel sessions drains one subscription window faster and gets the run
        // throttled halfway through a project, which wastes more quota than the parallelism buys.
        assertEquals(1, peak.get(), "more than one call reached the vendor at once");
    }
}
