package dev.fmangoni.forge.review;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.review.model.ReviewRound;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Runs the cross-review: the model that did not build the project reads the diff against the spec.
 *
 * <p>Reviews are capped well below a build's timeout. A reviewer is reading, not building, and one
 * that has spent twenty minutes is not deliberating — it has wandered off task, and the quota is
 * better spent on the next project.
 */
@RequiredArgsConstructor
public final class ReviewStage {

    private static final Duration REVIEW_CAP = Duration.ofMinutes(10);

    private final Agent reviewer;

    /** The reviewer's working directory: it needs to read the code the diff refers to. */
    private final Path repo;

    private final Path logDir;
    private final Path reviewFile;

    /** The model the spec asks of the reviewing vendor; blank leaves the CLI default. */
    private final String model;

    /**
     * @param diff the builder's work, from the scaffold baseline to HEAD
     * @return the findings; never throws, because an unusable review must not fail a passing project
     */
    public Review review(Spec spec, String diff) {
        return review(spec, diff, List.of());
    }

    /**
     * @param checks what the cheap per-feature pass found; its unproven claims become leads, and
     *               all of them are kept on the review so the report can say what was checked
     */
    public Review review(Spec spec, String diff, List<FeatureCheck> checks) {
        return review(spec, diff, checks, ReviewRound.first());
    }

    /**
     * @param round the findings from the last review, which this one must rule on by id
     */
    public Review review(Spec spec, String diff, List<FeatureCheck> checks, ReviewRound round) {
        return review(spec, diff, checks, round, null);
    }

    /**
     * @param design the name of the design a polish was built to, which the result is compared with,
     *               or null when the change was not built to a picture
     */
    public Review review(Spec spec, String diff, List<FeatureCheck> checks, ReviewRound round, String design) {
        // readOnly, which this was not for as long as the review stage has existed. The plain
        // constructor defaults to a writable turn, so every cross-review ran with permission to
        // change the code it was judging — Claude with bypassPermissions rather than the
        // restricted pairing, Antigravity in accept-edits rather than plan. Nothing is known to
        // have been altered by a reviewer, and that is luck rather than design: a model asked to
        // find problems is one prompt away from deciding to fix one.
        AgentResult result = reviewer.run(
                AgentTask.readOnly(repo, ReviewBrief.of(spec, diff, unproven(checks), round, design), REVIEW_CAP, logDir)
                        .using(model));

        Review review = result.blockedExternally()
                ? Review.unavailable("reviewer unavailable: " + result.outcome(),
                        result.retryAfter())
                : ReviewParser.parse(result.finalMessage());
        review.setReviewer(reviewer.kind()
                + (model == null || model.isBlank() ? "" : " (" + model + ")"));
        review.setScope(diff.length() + " characters of diff");
        review.setFeatureChecks(checks);

        persist(review);
        return review;
    }

    private static List<FeatureCheck> unproven(List<FeatureCheck> checks) {
        return checks.stream().filter(FeatureCheck::unproven).toList();
    }

    private void persist(Review review) {
        ReviewStore.save(reviewFile, review);
    }
}
