package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.feature.MissingClip;
import dev.fmangoni.forge.feature.MissingClips;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Whether another model has read it, and what it found. */
@Value
@Builder
public class StatusReview {

    /**
    * How the cross-review stands: none, unavailable, clean, or findings.
    *
    * <p>"unavailable" is the one worth surfacing. It means the other model never looked,
    * which is invisible on a card that otherwise reads as a finished, passing project.
    */
    private final String reviewState;

    /**
    * Why a cross-review cannot run right now, or blank when it can.
    *
    * <p>The reviewer is the vendor that did <em>not</em> build the project, so its
    * availability has nothing to do with the builder's. Without this the dashboard offered
    * a Run Cross-Review button that was certain to come straight back with a quota refusal
    * — the control looked available and was not.
    */
    private final String reviewBlockedReason;

    private final int blockingFindings;

    /**
     * The approved features the last gate found without a clip, when that is what it failed on.
     *
     * <p>Each one is a question for a person — still wanted, not worth a clip, or the same feature as
     * one that was filmed — and the dashboard asks it beside the project. Before, the only way to
     * answer was a form row folded away under "all ruled on", which nothing pointed to.
     */
    private final List<MissingClip> unfilmed;

    /**
     * How the last polish's review was decided — not reviewed again because only the look changed,
     * or reviewed in full and why — or blank.
     */
    private final String polishCheck;

    public static StatusReview of(RunState state, RunPaths paths, String reviewerWait) {
        return builder()
                .reviewState(reviewState(paths))
                .reviewBlockedReason(reviewerWait == null ? "" : reviewerWait)
                .blockingFindings(state.getBlockingFindings() == null ? 0 : state.getBlockingFindings().size())
                .unfilmed(unfilmed(state, paths))
                .polishCheck(state.getPolishCheck() == null ? "" : state.getPolishCheck())
                .build();
    }

    public static StatusReview queued() {
        return builder().reviewState("none").reviewBlockedReason("").unfilmed(List.of()).polishCheck("").build();
    }

    /** Read only when the last gate failed on recordings: otherwise the files say nothing current. */
    private static List<MissingClip> unfilmed(RunState state, RunPaths paths) {
        CommandResult failure = state.getLastFailure();
        if (failure == null || failure.command() == null || !failure.command().startsWith("recordings:")) {
            return List.of();
        }
        return MissingClips.of(paths.repo(), state.getPlan(), DecisionStore.load(paths.featuresFile()),
                state.getKeptClips());
    }

    /** Whether the other model actually looked, and what it said. */
    private static String reviewState(RunPaths paths) {
        Review review = ReviewStore.load(paths.reviewFile());
        if (review == null) {
            return "none";
        }
        // A parse failure is treated as "did not look" too: a reply nobody could read is no more
        // a review than a reply that never arrived.
        if (review.isUnavailable() || review.getParseError() != null) {
            return "unavailable";
        }
        return review.clean() ? "clean" : "findings";
    }
}
