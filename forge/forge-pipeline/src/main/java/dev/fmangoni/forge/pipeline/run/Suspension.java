package dev.fmangoni.forge.pipeline.run;

import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.state.RunState;
import java.time.Duration;
import java.time.Instant;
import lombok.experimental.UtilityClass;

/**
 * Suspends a run after a vendor refused a call, in a way that cannot wake it straight back up.
 *
 * <p>A suspension with no resume time used to mean "ready now": the queue refilled the slot the
 * moment the job ended, found the project eligible, and made the same call again. For a quota
 * refusal that stated no reset that was a tight loop of refusals; for a CLI whose sandbox could
 * not start a process it was a loop of full-length agent turns, each spending a model's time
 * explaining that it could not touch the repository, and none of them counted against any budget.
 *
 * <p>So every suspension here carries a time, and the two refusals that no wait can fix — not
 * logged in, and a broken tool — are handed to a person instead.
 */
@UtilityClass
public class Suspension {

    /**
     * How long to wait when the vendor refused without saying when to come back.
     *
     * <p>The same fifteen minutes the quota ledger holds a vendor for in that case, so the project
     * and its vendor come back together rather than one waking to find the other still closed.
     */
    public final Duration UNSTATED_WAIT = Duration.ofMinutes(15);

    /** Suspends the run in {@code resumeInto}, or holds it for a person if waiting cannot help. */
    public void after(RunState state, Phase resumeInto, AgentResult result) {
        state.setResumePhase(resumeInto);
        state.setPhase(Phase.SUSPENDED);
        state.setLastError(result.outcome() + ": " + result.finalMessage());
        String fix = whatAPersonMustDo(result.outcome());
        if (fix != null) {
            state.setRetryAfter(null);
            PersonHold.hold(state, fix);
            return;
        }
        state.setRetryAfter(orUnstatedWait(result.retryAfter()));
    }

    /** The vendor's stated reset, or the default wait when it named none. */
    public Instant orUnstatedWait(Instant stated) {
        return stated != null ? stated : Instant.now().plus(UNSTATED_WAIT);
    }

    /**
     * What has to happen before trying again is worth anything, or null if time alone will do.
     *
     * <p>Both of these are about the machine, not the project, so the same call from any project
     * would fail the same way.
     */
    private String whatAPersonMustDo(AgentOutcome outcome) {
        return switch (outcome) {
            case NOT_AUTHENTICATED -> "the agent CLI is not logged in; log it in by hand";
            case TOOL_BROKEN -> "the agent CLI's own sandbox would not start any process, so the "
                    + "turn could not touch the repository; check the CLI works by hand in this "
                    + "repository (for Codex, the windows.sandbox setting in ~/.codex/config.toml)";
            default -> null;
        };
    }
}
