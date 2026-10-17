package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The questions both schedulers ask before starting a project, answered once.
 *
 * <p>{@code JobRunner} drives the dashboard and {@code Scheduler} drives the unattended run, and
 * they used to answer these separately in near-identical code. That is not a tidiness complaint —
 * it has produced the same bug three times:
 *
 * <ul>
 *   <li>{@code parentAccepted} compared against {@code DONE} instead of {@code ACCEPTED}. Found
 *       and fixed in one, found again days later, unfixed, in the other.
 *   <li>{@code JobRunner} consulted the quota ledger and the vendor policy; {@code Scheduler}
 *       passed {@code null} for the ledger. The two therefore made different decisions about the
 *       same project depending on which one happened to look at it.
 *   <li>The suspension rule gained a clause — a project waiting on a reviewer may run when a
 *       <em>different</em> reviewer is free — and only the dashboard's copy got it. The unattended
 *       run went on waiting a full day for a vendor it no longer needed.
 * </ul>
 *
 * <p>So this is not shared for reuse. It is shared because two answers to one question is a bug
 * waiting for the next person to only fix one of them.
 */
public final class Eligibility {

    private final Path runsDir;

    /** Both may be absent: an unattended run built without them simply asks fewer questions. */
    private final Agents agents;
    private final QuotaLedger quota;

    public Eligibility(Path runsDir, Agents agents, QuotaLedger quota) {
        this.runsDir = runsDir;
        this.agents = agents;
        this.quota = quota;
    }

    public Eligibility(Path runsDir) {
        this(runsDir, null, null);
    }

    /**
     * Where this project's files live.
     *
     * <p>An improvement keeps its own state and logs but works inside its parent's repository, so
     * the two halves of its paths come from different places.
     */
    public RunPaths pathsFor(Spec spec, Wishlist wishlist) {
        RunPaths own = RunPaths.under(runsDir, spec.id());
        String owner = wishlist.repoOwner(spec);
        return owner.equals(spec.id())
                ? own
                : own.workingIn(RunPaths.under(runsDir, owner).repo());
    }

    /**
     * Whether an improvement's parent has been signed off.
     *
     * <p>ACCEPTED, not DONE. DONE means the acceptance commands passed and nobody has looked yet;
     * building on top of that is building on a foundation that may be about to be sent back. A
     * project that is not an improvement has nothing to wait for.
     */
    public boolean parentAccepted(Spec spec) {
        if (!spec.isImprovement()) {
            return true;
        }
        return spec.parentId()
                .flatMap(parent -> RunStore.survey(RunPaths.under(runsDir, parent).stateFile()))
                .map(state -> state.getPhase() == Phase.ACCEPTED)
                .orElse(false);
    }

    /**
     * Whether this run may take a turn now.
     *
     * <p>Finished projects are left alone. A suspended one waits out the window the CLI named —
     * unless what it is waiting for is a reviewer and a different one is free, because the window
     * belongs to the vendor that refused rather than to the project.
     */
    public boolean ready(RunState state) {
        if (state.getPhase().terminal()) {
            return false;
        }
        // Waiting for a person outranks every timer: it is set precisely when time alone has
        // stopped helping. Only an explicit Start, which never asks this, lifts it.
        if (state.getNeedsPerson() != null) {
            return false;
        }
        // Before anything else, including a free reviewer: a project whose step threw last time
        // will throw again straight away, and relaunching it immediately is how that became a loop.
        if (state.getCoolOffUntil() != null && Instant.now().isBefore(state.getCoolOffUntil())) {
            return false;
        }
        if (state.getPhase() != Phase.SUSPENDED) {
            return true;
        }
        if (awaitingReview(state) && anyReviewerFree(state)) {
            return true;
        }
        Instant retryAfter = state.getRetryAfter();
        return retryAfter == null || Instant.now().isAfter(retryAfter);
    }

    /** Whether the next call on this project goes to a reviewer rather than to its builder. */
    public boolean awaitingReview(RunState state) {
        return state.getPhase() == Phase.REVIEWING || state.getResumePhase() == Phase.REVIEWING;
    }

    /** Whether anyone who could review this project can be called right now. */
    public boolean anyReviewerFree(RunState state) {
        return reviewers(state).stream().anyMatch(kind -> quota == null || !quota.isBlocked(kind));
    }

    /** Who could review a project this one built, best first; empty when nothing is registered. */
    public List<AgentKind> reviewers(RunState state) {
        if (agents == null || state.getAssignedTo() == null) {
            return List.of();
        }
        return agents.reviewersFor(state.getAssignedTo());
    }

    /**
     * Whether this project is holding a quota wait belonging to a vendor it no longer needs.
     *
     * <p>{@code retryAfter} on the state is a per-project echo of one vendor's window. When a
     * review was refused and a different reviewer is now free, that timestamp answers a question
     * nobody is asking, and the orchestrator — which only sees the timestamp — would refuse to
     * resume for another day beside an idle model able to do the work.
     */
    public boolean waitingOnTheWrongVendor(RunState state) {
        return state.getPhase() == Phase.SUSPENDED
                && state.getRetryAfter() != null
                && awaitingReview(state)
                && anyReviewerFree(state);
    }

    /** A fresh run for a spec that has never been started. */
    public RunState create(Spec spec, Assignment assignment) {
        return RunState.builder()
                .id(spec.id())
                .title(spec.title())
                .assignedTo(assignment.decide(spec))
                .createdAt(Instant.now())
                .build();
    }
}
