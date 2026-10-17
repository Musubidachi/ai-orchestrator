package dev.fmangoni.forge.pipeline.run;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Recheck;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a review's blocking findings mean for the project: finished, sent back, or a person's call.
 *
 * <p>Every blocking finding must be fixed. The findings from the round before are carried forward
 * by id unless the reviewer says in terms that each is fixed or withdrawn, and keep the attempts
 * already spent on them; new ones start at none. Each gets {@link #MAX_ATTEMPTS} turns. A finding
 * still present after its last, or a reviewer still raising new ones after {@link #MAX_ROUNDS}
 * repair turns, holds the project for a person rather than letting it finish or go round again.
 *
 * <p>The old rule was one repair turn, after which the project went to DONE with whatever the next
 * review found. Four of five finished projects here were DONE with blocking findings on them, and
 * the ones sent back by hand came back reworded.
 *
 * <p>Here rather than beside the review step because the repair turn needs the same numbers to
 * tell the agent which attempt it is on.
 */
public final class ReviewLoop {

    /** Turns one finding gets. The second carries the reviewer's reason the first did not work. */
    public static final int MAX_ATTEMPTS = 2;

    /**
     * Repair turns in one review loop, whatever the findings.
     *
     * <p>Exists because each fresh read raises something new, and attempts are counted per
     * finding. Without it a project could go round for as long as the reviewer kept noticing things.
     */
    public static final int MAX_ROUNDS = 4;

    private ReviewLoop() {
    }

    /**
     * The blocking findings that stand after this review, carried and new, each with its id.
     *
     * <p>A previous finding the reviewer did not mention stays: a defect is only settled when the
     * reviewer says so. Written back into the review too, so the report and the form show what
     * the project is actually being held to rather than only what was new this round.
     */
    public static List<Finding> outstanding(List<Finding> previous, Review review) {
        Map<String, Recheck> verdicts = new HashMap<>();
        for (Recheck recheck : review.getRechecks() == null ? List.<Recheck>of() : review.getRechecks()) {
            if (recheck.getId() != null) {
                verdicts.put(recheck.getId().strip(), recheck);
            }
        }
        List<Finding> standing = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Finding finding : previous == null ? List.<Finding>of() : previous) {
            Recheck verdict = verdicts.get(finding.getId());
            if (verdict != null && verdict.settled()) {
                continue;
            }
            finding.setStillPresent(verdict == null
                    ? "the reviewer did not say it was fixed"
                    : verdict.getNote());
            standing.add(finding);
            ids.add(finding.getId());
        }
        for (Finding finding : review.blocking()) {
            finding.setId(ReviewItem.idOf(finding));
            if (ids.add(finding.getId())) {
                standing.add(finding);
            }
        }
        List<Finding> all = new ArrayList<>(review.notes());
        all.addAll(standing);
        review.setFindings(all);
        return standing;
    }

    /**
     * Sends the findings back, or holds the project for a person. Never finishes it: a caller with
     * no findings standing does not come here.
     */
    public static void sendBackOrHold(RunState state, List<Finding> standing) {
        List<Finding> exhausted = standing.stream()
                .filter(finding -> finding.getAttempts() >= MAX_ATTEMPTS).toList();
        state.setPhase(Phase.REVIEW_REPAIR);
        if (!exhausted.isEmpty()) {
            PersonHold.hold(state, "still not fixed after " + MAX_ATTEMPTS + " attempts: "
                    + exhausted.stream().map(Finding::getSummary).collect(Collectors.joining("; ")));
            return;
        }
        if (state.getReviewRepairs() >= MAX_ROUNDS) {
            PersonHold.hold(state, "the reviewer is still raising blocking findings after "
                    + MAX_ROUNDS + " repair turns");
            return;
        }
        // Counted as they are sent, not when the turn runs: a turn suspended on quota and resumed
        // is still the same attempt.
        standing.forEach(finding -> finding.setAttempts(finding.getAttempts() + 1));
        state.setReviewRepairs(state.getReviewRepairs() + 1);
    }
}
