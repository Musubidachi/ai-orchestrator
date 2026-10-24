package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.gate.LookCheck;
import dev.fmangoni.forge.jobs.queue.PipelineJobs;
import dev.fmangoni.forge.jobs.work.Eligibility;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.ReviewLoop;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Sends a finished project back into the pipeline: for another session, for a fresh review, or to
 * have its blocking findings fixed.
 */
public final class Reopening {

    private final Projects projects;
    private final JobPool pool;
    private final PipelineJobs pipeline;

    public Reopening(Projects projects, JobPool pool, PipelineJobs pipeline) {
        this.projects = projects;
        this.pool = pool;
        this.pipeline = pipeline;
    }

    /**
     * Reopens a finished project for another session.
     *
     * <p>A project reaches DONE when its acceptance commands pass, which is the right rule — but the
     * commands only check what the spec author thought to check, so an agent can hand back
     * "complete" while its own plan still lists work. Without this the only options were editing
     * state.json by hand or writing a sub-spec, neither of which suits "spend one more session".
     *
     * <p>Re-enters at CONTINUING rather than starting over: the repository, the git history and the
     * handoff are all still there, and the next turn should carry on from them.
     */
    public String reopen(String id) {
        if (pool.isRunning(id)) {
            return "already running";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        String notHere = Eligibility.notHere(projects.home().runs(), found.get().spec());
        if (notHere != null) {
            return notHere;
        }
        RunPaths paths = found.get().paths();
        RunState state = RunStore.load(paths.stateFile()).orElse(null);
        if (state == null || !state.getPhase().terminal()) {
            return "not finished, so there is nothing to reopen";
        }
        state.setPhase(Phase.CONTINUING);
        state.setLastError(null);
        // A fresh attempt gets a fresh budget. Reopening a project that parked on rounds it never
        // really used would otherwise re-park on the first gate failure. The same for the review loop.
        state.setRepairRounds(0);
        state.setReviewRepairs(0);
        RunStore.save(paths.stateFile(), state);
        return pipeline.start(id);
    }

    /**
     * Runs a parked project's gate again, and nothing else.
     *
     * <p>For when what stopped the gate was not the code. A ruling on a feature, a clip owed under a
     * name nobody films any more, a command that failed for a reason outside the repository: once a
     * person has dealt with it, the question is whether the gate now passes, and an agent session is
     * the wrong price for that answer. Run Another Session would also brief the agent with the old
     * failure, and 003 showed what follows: told a clip was missing that a person had since ruled
     * out, an agent goes and films it under the name it was taken off.
     *
     * <p>No session is spent and none is counted, so it is not stopped by the session budget either.
     * If the gate passes the project carries on as any passing project does, through the security
     * check and the cross-review; if it fails it parks again, saying so.
     */
    public String recheck(String id) {
        String outcome = withState(id, (paths, state) -> {
            if (state.getPhase() != Phase.PARKED) {
                return "only a parked project's gate can be checked again on its own";
            }
            if (state.getLastFailure() == null) {
                return "it did not park on its gate — " + state.getLastError()
                        + " — so use Run Another Session";
            }
            state.setPhase(Phase.GATING);
            state.setGateOnly(true);
            state.setLastError(null);
            RunStore.save(paths.stateFile(), state);
            return null;
        });
        return outcome != null ? outcome : pipeline.start(id);
    }

    /**
     * Approves the default look a project's gate stopped on, and checks its gate again.
     *
     * <p>For a version that changed the main screen on purpose: the old reference is set aside, not
     * deleted ({@link LookCheck#retire}), and the gate run next adopts the look the person has just
     * seen as the new default. Only after a gate that stopped on the look itself, so the button
     * cannot be used to wave anything else through.
     */
    public String approveLook(String id) {
        String refused = withState(id, (paths, state) -> {
            if (state.getPhase() != Phase.PARKED || !LookCheck.failed(state.getLastFailure())) {
                return "its gate did not stop on the default look, so there is no new look to approve";
            }
            LookCheck.retire(paths.lookReference(), id);
            return null;
        });
        return refused != null ? refused : recheck(id);
    }

    /**
     * Sends a finished project back to the other model for a fresh cross-review.
     *
     * <p>Needed because a review can fail to happen at all. The reviewing vendor is the one that
     * did <em>not</em> build the project, so it is quite normal for it to be out of quota exactly
     * when the builder finishes — and a project that reached the end unreviewed has no route back
     * to a reviewer without this. Reopening it for another session is the wrong tool: the code is
     * finished, and what is missing is a second opinion on it.
     *
     * <p>Reviews the current HEAD against the baseline, so it is also the right button after
     * changes have been made since the last review.
     *
     * <p>Keeps the blocking findings it had, so the reviewer rules on each by id before looking for
     * anything new. Clearing them made every review a fresh read, and a fresh read of a defect nobody
     * fixed came back as a new finding with a new name — so nothing ever said "this one is still
     * here". The repair budget starts again: pressing this is a person asking for another go.
     */
    public String rereview(String id) {
        String outcome = withState(id, (paths, state) -> {
            if (!state.getPhase().terminal()) {
                return "it is still working; a review runs once it stops";
            }
            if (state.getPhase() == Phase.ACCEPTED) {
                // Never silently undo the one decision a person makes. A review can only end at
                // DONE, so running one here would quietly withdraw an acceptance already given.
                return "already accepted — use Request Changes if you want it looked at again";
            }
            if (state.getBaselineCommit() == null) {
                return "it has no baseline to compare against";
            }
            restoreRepairOwedForAReviewThatNeverRan(paths, state);
            // Asked for by name, so it is the full review whatever a polish would have earned.
            state.setPolishBase(null);
            state.setReviewRepairs(0);
            state.setPhase(Phase.REVIEWING);
            state.setLastError(null);
            RunStore.save(paths.stateFile(), state);
            return null;
        });
        return outcome != null ? outcome : pipeline.start(id);
    }

    /**
     * Sends a finished project's blocking findings straight back to the agent that built it.
     *
     * <p>The loop that makes every blocking finding mandatory only starts at a review, so a project
     * that finished before it existed sits at DONE with its findings and no way into it. The two
     * routes there were both the long way round: Review Again buys a whole review on the reviewer's
     * best model before anything is fixed, and the report's form means ruling on every item one at
     * a time. This is the direct one — the findings are already written down, so the next thing to
     * pay for is the fix.
     *
     * <p>From there it is the ordinary loop: the agent fixes each with a test or disputes it with
     * one, the gate runs, and the reviewer rules on each finding by id. Pressing this is a person
     * asking, so the findings start with their attempts unspent.
     *
     * <p>Only where the gate has passed — DONE, or held after the findings' attempts ran out. A
     * parked project is parked on something else, and telling an agent its acceptance commands
     * pass when they do not sends it after the wrong problem.
     */
    public String fixBlocking(String id) {
        String outcome = withState(id, (paths, state) -> {
            if (state.getPhase() == Phase.ACCEPTED) {
                return "already accepted — use Run Another Session if you want more work done on it";
            }
            boolean held = state.getPhase() == Phase.REVIEW_REPAIR && state.getNeedsPerson() != null;
            if (state.getPhase() != Phase.DONE && !held) {
                return state.getPhase() == Phase.PARKED
                        ? "it is parked on something else — " + state.getLastError()
                          + " — so use Run Another Session"
                        : "it is still working; its blocking findings are sent back by the review itself";
            }
            List<Finding> blocking = blockingOn(paths, state);
            if (blocking.isEmpty()) {
                return "there are no blocking findings to fix";
            }
            for (Finding finding : blocking) {
                if (finding.getId() == null || finding.getId().isBlank()) {
                    finding.setId(ReviewItem.idOf(finding));
                }
                finding.setAttempts(0);
            }
            state.setBlockingFindings(blocking);
            state.setReviewRepairs(0);
            state.setLastError(null);
            PersonHold.release(state);
            ReviewLoop.sendBackOrHold(state, blocking);
            RunStore.save(paths.stateFile(), state);
            return null;
        });
        return outcome != null ? outcome : pipeline.start(id);
    }

    /**
     * The findings this project is held to: what its state records, or failing that its last review.
     *
     * <p>The state is the authority, because the loop carries findings forward there. The review
     * file is the fallback for a project finished before the state kept them.
     */
    private static List<Finding> blockingOn(RunPaths paths, RunState state) {
        if (state.getBlockingFindings() != null && !state.getBlockingFindings().isEmpty()) {
            return new ArrayList<>(state.getBlockingFindings());
        }
        Review review = ReviewStore.load(paths.reviewFile());
        return review == null || review.isUnavailable() || review.getParseError() != null
                ? new ArrayList<>() : new ArrayList<>(review.blocking());
    }

    /**
     * A review that never happened cannot have spent the repair round it was owed.
     *
     * <p>Without this, a project whose reviewer was rate-limited is permanently denied the one
     * repair a real review is entitled to — punished for the other vendor's quota window.
     */
    private static void restoreRepairOwedForAReviewThatNeverRan(RunPaths paths, RunState state) {
        Review previous = ReviewStore.load(paths.reviewFile());
        if (previous == null || previous.isUnavailable() || previous.getParseError() != null) {
            state.setReviewRepairUsed(false);
        }
    }

    /**
     * Loads a project's state and applies an action to it, or explains why it could not.
     *
     * <p>Never for a project whose repository is not here ({@link Eligibility#notHere}): every button
     * here changes the state and then starts the pipeline, and there would be nothing to start in.
     */
    private String withState(String id, BiFunction<RunPaths, RunState, String> action) {
        if (pool.isRunning(id)) {
            return "still running";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        String notHere = Eligibility.notHere(projects.home().runs(), found.get().spec());
        if (notHere != null) {
            return notHere;
        }
        RunPaths paths = found.get().paths();
        RunState state = RunStore.load(paths.stateFile()).orElse(null);
        if (state == null) {
            return "it has not run yet";
        }
        return action.apply(paths, state);
    }
}
