package dev.fmangoni.forge.agent;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.Usage;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;

/**
 * Limits how many invocations of one vendor's CLI run at once.
 *
 * <p>The limit belongs here rather than on the project because a project does not use a single
 * vendor throughout: one built by Claude is reviewed by Codex. Serialising whole projects per
 * vendor would therefore deadlock the review stage against another project's build, whereas
 * limiting each individual call lets a project hold no vendor at all while its gate runs — which is
 * where a build spends most of its wall-clock time.
 *
 * <p>One permit per vendor is the intended setting. Fanning out more sessions against a single
 * subscription drains the same quota window faster and invites a throttle halfway through a
 * project, which wastes more than the parallelism gains.
 */
@RequiredArgsConstructor
public final class ThrottledAgent implements Agent {

    private final Agent delegate;
    private final Semaphore permits;

    /**
     * Where a reported limit is written down.
     *
     * <p>Recorded here because this is the only place that knows which vendor actually made the
     * call. A review turn runs on the opposite vendor to the build, so attributing a limit to the
     * project's assigned agent would blame the wrong subscription.
     */
    private final QuotaLedger quota;

    /**
     * Where what the turn actually cost is written down.
     *
     * <p>Same reason as the quota ledger: this is the only place that knows which subscription the
     * call was made against. Null is tolerated so a caller that does not care about measurement
     * does not have to construct one.
     */
    private final UsageLedger usage;

    public static ThrottledAgent wrapping(Agent delegate, int concurrency, QuotaLedger quota) {
        return wrapping(delegate, concurrency, quota, null);
    }

    public static ThrottledAgent wrapping(Agent delegate, int concurrency, QuotaLedger quota,
                                          UsageLedger usage) {
        // Fair ordering: without it a long queue can starve one project indefinitely, and an
        // unattended pipeline has no one watching to notice.
        return new ThrottledAgent(delegate, new Semaphore(concurrency, true), quota, usage);
    }

    @Override
    public AgentKind kind() {
        return delegate.kind();
    }

    /**
     * Notes a limit, or clears one that has evidently passed.
     *
     * <p>A completed call is the only proof that a window has actually reopened, which is better
     * evidence than the reset time the CLI predicted earlier.
     *
     * <p>A CLI that is logged out, or whose sandbox will not start a process, is noted too. Neither
     * is quota, but both are about the vendor on this machine rather than the project that found
     * them, and the next project to call it would meet the same wall. Unrecorded, the reviewer
     * queue kept counting a logged-out vendor as free and sent every waiting review straight back
     * to it. They carry no reset time, so the ledger holds them briefly rather than indefinitely.
     */
    private void remember(AgentResult result) {
        if (quota == null) {
            return;
        }
        if (result.outcome() == AgentOutcome.RATE_LIMITED) {
            quota.record(kind(), result.retryAfter(), result.finalMessage());
        } else if (result.outcome() == AgentOutcome.NOT_AUTHENTICATED
                || result.outcome() == AgentOutcome.TOOL_BROKEN) {
            quota.record(kind(), null, result.outcome() + ": " + result.finalMessage());
        } else if (result.outcome() == AgentOutcome.COMPLETED) {
            quota.clear(kind());
        }
    }

    /**
     * Records what the turn cost, and what the window turned out to hold when it ran out.
     *
     * <p>A refused call carries no usage of its own — nothing ran — but it does carry the one
     * measurement that cannot be taken any other way: everything spent before it is the size of a
     * window, as closely as that can be known from outside the vendor.
     */
    private void meter(AgentResult result) {
        if (usage == null) {
            return;
        }
        try {
            if (result.outcome() == AgentOutcome.RATE_LIMITED) {
                usage.windowExhausted(kind());
                return;
            }
            usage.record(kind(), Usage.from(kind(),
                    result.proc() == null ? "" : result.proc().stdout()));
        } catch (RuntimeException e) {
            // Measurement is not worth failing a build over.
            System.err.println("could not record usage for " + kind() + ": " + e.getMessage());
        }
    }

    @Override
    public AgentResult run(AgentTask task) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a " + kind() + " slot", e);
        }
        try {
            AgentResult result = delegate.run(task);
            remember(result);
            meter(result);
            return result;
        } finally {
            permits.release();
        }
    }
}
