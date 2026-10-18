package dev.fmangoni.forge.jobs.submit;

import dev.fmangoni.forge.brief.repair.FeedbackBrief;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.jobs.queue.PipelineJobs;
import dev.fmangoni.forge.jobs.queue.StartRules;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What follows from a submission — at most one thing.
 *
 * <p>One act, however much was submitted. Features and review items used to be two forms with two
 * endpoints, and the first one pressed reopened the project, which made the second refuse:
 * approving a recording cost a review. Whatever is submitted together is now decided together, and
 * only the strongest consequence in it happens.
 */
final class SubmissionOutcome {

    private final JobPool pool;
    private final StartRules rules;
    private final PipelineJobs pipeline;

    SubmissionOutcome(JobPool pool, StartRules rules, PipelineJobs pipeline) {
        this.pool = pool;
        this.rules = rules;
        this.pipeline = pipeline;
    }

    String act(Submission submission) {
        String refused = refusal(submission);
        return refused != null ? submission.kept() + refused : consequence(submission);
    }

    /** Why Forge may not act on this submission now, or null when it may. What was typed is already saved. */
    private String refusal(Submission submission) {
        RunState state = submission.state();
        if (pool.isRunning(submission.id())) {
            // This used to say exactly this and do nothing of the kind: nothing read the saved
            // requests again when the session ended, so they sat on disk until somebody happened to
            // submit the form a second time. The marker is what makes the sentence true.
            markPending(submission.paths());
            return "a session is already running; this will be acted on when it finishes";
        }
        if (state.getPhase() == Phase.ACCEPTED) {
            // Never silently undo the one decision a person makes. Reopening an accepted project
            // is reasonable, but it is a separate act and should be an explicit one.
            return "already accepted — use Reopen if you want more work done on it";
        }
        if (state.getPhase() != Phase.DONE && !heldOnReview(state)) {
            // Distinguished because the report offers the form in this state too, and being told
            // "only a project waiting for approval can be reviewed" about a project that is plainly
            // finished reads as a bug rather than as an explanation.
            return rules.awaitingReview(state)
                    ? "the cross-review has not run yet — it cannot be accepted until another model has read it"
                    : "it is not finished, so there is nothing to accept yet";
        }
        Review review = ReviewStore.load(submission.paths().reviewFile());
        if (review == null || review.isUnavailable() || review.getParseError() != null) {
            return "run the cross-review before accepting — nothing but the author has read this";
        }
        return null;
    }

    /**
     * Held for a person after its blocking findings could not be fixed in the attempts they had.
     *
     * <p>Rulable like a finished project, because that is what the hold is asking for: accept it
     * as it stands, send particular findings back with a word of your own, or rule one acceptable.
     */
    private static boolean heldOnReview(RunState state) {
        return state.getPhase() == Phase.REVIEW_REPAIR && state.getNeedsPerson() != null;
    }

    private String consequence(Submission submission) {
        RunState state = submission.state();
        List<Decision> rulings = submission.rulings();
        String kept = submission.kept();

        // Only rulings on claims the agent still makes. A verdict is about a specific statement,
        // and the agent rewrites that list at the end of every session — on this machine one
        // project ended up with four "needs work" rulings on claims that no longer existed, which
        // the page shows read-only and offers no way to withdraw. Left in, they would have blocked
        // acceptance for good and sent the agent back to work on four dead sentences. The notes
        // survive either way; it is the verdict that expires with the claim it was about.
        List<Decision> live = Decisions.current(submission.items(), submission.decisions(), submission.merged());
        List<Decision> sendBack = live.stream().filter(Decision::wantsChanges).toList();

        // Everything written on the page goes with whichever session this starts, whatever the
        // reason it starts. The recording-only branch used to clear this to null, which is exactly
        // how a request typed beside a feature was saved, displayed, and never read by an agent.
        String brief = FeedbackBrief.itemised(live, rulings, Features.toBuild(Feature.of(state.getPlan()), rulings),
                reviewerDetail(submission.paths()));
        List<Decision> featuresToChange = rulings.stream().filter(Features::changes).toList();

        if (!sendBack.isEmpty() || !featuresToChange.isEmpty()) {
            // Re-enters at CONTINUING rather than charging a repair round: being asked for
            // something different is not the same as failing a check.
            delivered(submission.paths(), rulings, featuresToChange);
            return kept + "sent back; " + reopenWith(submission, brief);
        }
        if (!submission.unfilmed().isEmpty()) {
            // Asking for a recording is new work too, and the same door leads to it.
            int owed = submission.unfilmed().size();
            return kept + owed + (owed == 1 ? " feature needs a recording" : " features need recordings")
                    + "; " + reopenWith(submission, brief.isBlank() ? null : brief);
        }
        List<Decision> unheard = unheardFeatureNotes(rulings);
        if (!unheard.isEmpty()) {
            return kept + (unheard.size() == 1
                    ? "your note on a feature goes to the agent"
                    : unheard.size() + " notes on features go to the agent")
                    + " before this can be accepted; " + reopenWith(submission, brief);
        }
        if (submission.decisions() == null || submission.decisions().isEmpty()) {
            // Features alone never accept a project. An acceptance means somebody went through
            // the review list, and a submission that did not carry it has not done that.
            return kept.isBlank() ? "saved" : kept + "nothing else is outstanding";
        }
        state.setPhase(Phase.ACCEPTED);
        state.setLastError(null);
        // Accepting is the person's answer to whatever the hold was asking.
        state.setNeedsPerson(null);
        RunStore.save(submission.paths().stateFile(), state);
        return "accepted";
    }

    /**
     * What the reviewer said about each blocking finding, keyed by the text the form shows.
     *
     * <p>The form shows a finding as one line — file and summary — and a finding sent back used to
     * reach the agent as exactly that line and nothing else: "deal with it as stated", about a
     * defect whose explanation was in a file the agent was never shown. Five findings sent back
     * that way came back in the next review.
     */
    private static Map<String, String> reviewerDetail(RunPaths paths) {
        Review review = ReviewStore.load(paths.reviewFile());
        Map<String, String> detail = new HashMap<>();
        if (review == null) {
            return detail;
        }
        for (Finding finding : review.blocking()) {
            StringBuilder said = new StringBuilder(finding.getDetail() == null ? "" : finding.getDetail());
            if (finding.getStillPresent() != null && !finding.getStillPresent().isBlank()) {
                said.append(said.isEmpty() ? "" : " ").append("It was sent back before, and the reviewer ")
                        .append("says the change did not fix it: ").append(finding.getStillPresent());
            }
            if (!said.isEmpty()) {
                detail.put(ReviewItem.textOf(finding), said.toString());
            }
        }
        return detail;
    }

    /**
     * Feature notes no session has been given yet.
     *
     * <p>A note beside a feature is about the feature. Its radio buttons rule on the recording —
     * worth seeing, not worth a clip — so "Worth seeing" plus "slight modification: offer an ad from
     * more credit" is a request, and accepting over it drops the request on the floor. That is what
     * happened to 003: saved, shown on the report, never read by an agent. Declined features are
     * left out; a note on something you do not want is not work.
     */
    private static List<Decision> unheardFeatureNotes(List<Decision> rulings) {
        return rulings.stream()
                .filter(ruling -> !Features.declined(ruling))
                .filter(Decision::hasUnheardComments)
                .toList();
    }

    /** Hands the brief to the next session and starts it; says how the start went. */
    private String reopenWith(Submission submission, String brief) {
        RunState state = submission.state();
        state.setUserFeedback(brief);
        heard(submission.paths(), submission.rulings());
        state.setPhase(Phase.CONTINUING);
        state.setLastError(null);
        // New work, with a review loop of its own: four repair turns spent on the last request are
        // not this one's. Carried over, 003's Christmas theme was held on its first finding.
        state.setReviewRepairs(0);
        RunStore.save(submission.paths().stateFile(), state);
        return pipeline.start(submission.id());
    }

    /**
     * Settles a "needs changes" back to approved once the request has been handed to a session.
     *
     * <p>Otherwise the verdict outlives the work it asked for. The session builds the change, the
     * project finishes, and the next submission — including the one meant to accept it — finds
     * "needs changes" still on record and reopens the project again, for ever. The notes stay; it
     * is only the instruction that has been carried out. If the result is still wrong, saying so
     * again is one click.
     */
    private static void delivered(RunPaths paths, List<Decision> rulings, List<Decision> handedOver) {
        if (handedOver.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        handedOver.forEach(ruling -> ids.add(ruling.getItemId()));
        for (Decision ruling : rulings) {
            if (ids.contains(ruling.getItemId())) {
                ruling.setVerdict(Features.APPROVED);
            }
        }
        DecisionStore.save(paths.featuresFile(), rulings);
    }

    /** Marks every feature note as given to a session, now that the brief carrying it is stored. */
    private static void heard(RunPaths paths, List<Decision> rulings) {
        Instant now = Instant.now();
        boolean any = false;
        for (Decision ruling : rulings) {
            if (ruling.hasUnheardComments()) {
                ruling.setHeardAt(now);
                any = true;
            }
        }
        if (any) {
            DecisionStore.save(paths.featuresFile(), rulings);
        }
    }

    private static void markPending(RunPaths paths) {
        try {
            JsonFile.write(paths.pendingFeedbackFile(), "{}");
        } catch (IOException e) {
            System.err.println("could not note pending feedback for " + paths.root() + ": " + e);
        }
    }
}
