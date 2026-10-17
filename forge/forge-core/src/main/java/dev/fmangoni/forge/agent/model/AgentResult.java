package dev.fmangoni.forge.agent.model;

import dev.fmangoni.forge.process.ProcResult;
import java.time.Instant;
import java.util.Optional;

/**
 * Result of one agent turn.
 *
 * <p>{@code finalMessage} is the agent's own account of what it did. Forge records it for the
 * report and never acts on it: whether the project is done is decided by running the spec's
 * acceptance commands, not by reading this field.
 */
public record AgentResult(
        AgentOutcome outcome,
        String finalMessage,
        ProcResult proc,
        Instant retryAfter,

        /** The agent session this turn ran in, for the next turn to resume. */
        String sessionId) {

    public AgentResult(AgentOutcome outcome, String finalMessage, ProcResult proc,
                       Instant retryAfter) {
        this(outcome, finalMessage, proc, retryAfter, null);
    }

    public AgentResult withSession(String sessionId) {
        return new AgentResult(outcome, finalMessage, proc, retryAfter, sessionId);
    }

    /** When set, the wall-clock time at which the exhausted quota window is expected to reset. */
    public Optional<Instant> retryAfterTime() {
        return Optional.ofNullable(retryAfter);
    }

    public boolean completed() {
        return outcome == AgentOutcome.COMPLETED;
    }

    /** True when the cause is environmental, so the project should suspend rather than lose a repair round. */
    public boolean blockedExternally() {
        return outcome == AgentOutcome.RATE_LIMITED
                || outcome == AgentOutcome.NOT_AUTHENTICATED
                || outcome == AgentOutcome.TOOL_BROKEN;
    }
}
