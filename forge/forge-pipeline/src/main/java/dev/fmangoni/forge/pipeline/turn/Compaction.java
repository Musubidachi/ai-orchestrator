package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.state.RunState;
import lombok.experimental.UtilityClass;

/**
 * Decides when to stop resuming a conversation and start a fresh one from the digest.
 *
 * <p>These CLIs replay the whole transcript on every resumed turn, so a long-running project pays
 * for its entire history on each call. One project here reached 5 MB of transcript across six
 * sessions; the seventh session would have carried all of it, most of it spent on work already
 * finished, dead ends already abandoned, and gate output already acted on.
 *
 * <p>Dropping the conversation is only safe because the durable part was written down while the
 * agent still had it: the repository, the git history, the spec, and {@link Plan#getDigest()}.
 * Without a usable digest this never compacts, however long the conversation gets — a fresh session
 * with no briefing is worse than an expensive one that knows what it is doing.
 */
@UtilityClass
public class Compaction {

    /**
     * Whether the next turn should begin a new conversation.
     *
     * <p>Only at a clean boundary. Compacting mid-repair would drop the context of the failure
     * being chased, which is exactly the context that turn needs, so a project that is stuck keeps
     * its history until it is unstuck.
     *
     * @param every compact after this many sessions on one conversation; 0 disables it entirely
     */
    public boolean due(RunState state, int every) {
        if (every <= 0) {
            return false;
        }
        Plan plan = state.getPlan();
        if (plan == null || !plan.usable() || plan.getDigest() == null || plan.getDigest().isBlank()) {
            return false;
        }
        return atACleanBoundary(state) && state.getSessionsOnConversation() >= every;
    }

    /**
     * A turn that ended by choosing to stop, rather than one that ended in trouble.
     *
     * <p>Repair rounds and review repairs are chasing a specific failure; the conversation holding
     * that failure is worth keeping until it is resolved. A build or continuation that produced a
     * usable handoff has already said everything it needs to say in the digest.
     */
    private boolean atACleanBoundary(RunState state) {
        // Listed exhaustively rather than with a default, so adding a phase is a compile error
        // here instead of a silent decision about whether it is safe to forget a conversation.
        return switch (state.getPhase()) {
            case SCAFFOLDED, CONTINUING, GATING, SECURITY_CHECK, DONE, ACCEPTED -> true;
            case NEW, PLANNING, REPAIRING, REVIEWING, REVIEW_REPAIR, SECURITY_REPAIR, SUSPENDED,
                 PARKED -> false;
        };
    }
}
