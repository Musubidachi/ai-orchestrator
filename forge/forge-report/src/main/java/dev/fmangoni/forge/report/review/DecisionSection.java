package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The part you act on, placed after the evidence and the cross-review rather than beside a summary.
 *
 * <p>Rendered even when the cross-review has not run. It used to be replaced by a sentence saying to
 * come back later, which meant a project whose reviewer was rate-limited offered no way to write
 * anything down at all — so the reading you had just done was lost, and the note you would have
 * left with it. What the missing review actually blocks is <em>accepting</em>, and that is the only
 * thing the form withholds.
 */
@UtilityClass
public class DecisionSection {

    public String render(RunPaths paths, RunState state, Review review) {
        List<ReviewItem> items = ReviewItem.of(state.getPlan(), review, SecurityReport.load(paths.securityFile()));
        List<Decision> stored = Decisions.realign(items, DecisionStore.load(paths.decisionsFile()));
        List<Sitting> history = ReviewHistory.load(paths.reviewHistoryFile());

        return DecisionForm.render(
                state.getId(),
                items,
                stored,
                Decisions.orphans(items, stored),
                featureAndIdeaRulings(paths, state),
                notice(state, review),
                history)
                + ReviewHistorySection.render(history, stored);
    }

    /**
     * Why a submission cannot be acted on yet, or blank when it can. Shared with the quick review,
     * so the two pages cannot give different reasons for the same project.
     */
    public String notice(RunState state, Review review) {
        // A project suspended on its way into review has finished building and passed its gate;
        // what it is waiting for is the other vendor's quota window. That is the exact state this
        // was reported in, and it is the state where having nowhere to write is most annoying —
        // the reading is done, the reviewer is hours away, and the thought is now.
        boolean awaitingReview = state.getPhase() == Phase.SUSPENDED && state.getResumePhase() == Phase.REVIEWING;
        boolean held = state.getNeedsPerson() != null && !state.getPhase().terminal();
        boolean working = !state.getPhase().terminal() && !awaitingReview && !held;
        boolean unreviewed = awaitingReview
                || review == null || review.isUnavailable() || review.getParseError() != null;
        return held ? heldNotice(state.getNeedsPerson()) : blockedNotice(working, awaitingReview, unreviewed);
    }

    /**
     * What a held project is waiting for, and the three ways to answer it.
     *
     * <p>A project held because its blocking findings survived their attempts is not "running", and
     * telling the person a session is in progress — which is what this said for every unfinished
     * phase — sends them away from the one page where they can decide.
     */
    private String heldNotice(String why) {
        return "Waiting for you: " + why + ". Send back any finding that should be tried again, with "
                + "a note if you can say what is wrong, or rule it Fine as it is to let the project "
                + "stand with it. Pressing Start on the dashboard instead gives the agent one more "
                + "attempt at all of them.";
    }

    /** Both folds of the one form: the features the project has, and the ones it could have. */
    private String featureAndIdeaRulings(RunPaths paths, RunState state) {
        List<Decision> rulings = DecisionStore.load(paths.featuresFile());
        List<Feature> features = Feature.of(state.getPlan());
        List<Feature> ideas = Feature.ideas(state.getPlan());
        return FeatureRulings.render(features, ideas, rulings)
                + IdeaRulings.render(ideas, Features.listed(features, ideas, rulings), rulings);
    }

    /**
     * Why this cannot be acted on yet, when it cannot.
     *
     * <p>The form is offered anyway. It used to disappear while a session was running, which is
     * exactly the moment somebody is watching the project and has something to say about it — and
     * the one report of this ended with a review typed into a page that then refused it, because
     * the session had started between the reading and the submitting. What a running session
     * blocks is <em>acting</em>; writing it down is never the thing to withhold.
     */
    private String blockedNotice(boolean working, boolean awaitingReview, boolean unreviewed) {
        if (working) {
            return "A session is running on this project, so nothing here can be acted on until "
                    + "it stops. Submit anyway: what you write is saved now and is in front of "
                    + "the next turn.";
        }
        if (!unreviewed) {
            return "";
        }
        return (awaitingReview
                ? "The cross-review has not run yet — it is waiting on the other model's quota "
                  + "window, so this cannot be accepted yet. "
                : "Nothing except the model that wrote this has read it, so it cannot be accepted "
                  + "yet — run the cross-review from the dashboard. ")
                + "Your notes are saved either way.";
    }
}
