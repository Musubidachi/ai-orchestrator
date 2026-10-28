package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.ReviewLoop;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Suspension;
import dev.fmangoni.forge.plan.FindingAnswer;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.FileExcerpt;
import dev.fmangoni.forge.review.LongFiles;
import dev.fmangoni.forge.review.ReviewStage;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.feature.FeaturePass;
import dev.fmangoni.forge.review.feature.FeatureReview;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.review.model.ReviewRound;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cross-review, in two passes.
 *
 * <p>A reviewer's cheapest model reads the code once per claimed feature and says which claims it
 * cannot find, passing any it could not answer to the next reviewer; one best model then reviews
 * the whole change once, with those claims in front of it. The expensive judgement is made once, and the legwork before it is not made
 * expensive — which is the whole point of the split.
 */
public final class ReviewStep {

    /**
     * How much diff one review is allowed to carry.
     *
     * <p>Roughly 60k characters, which is a substantial project's worth of real source once lock
     * files and build output are excluded. The bound exists because the reviewer is the scarce
     * vendor's quota: an unbounded diff turns one project into an entire window.
     */
    public static final int REVIEW_DIFF_CAP = 60_000;

    private final RunContext run;

    public ReviewStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        RunState state = run.state();
        // A visual polish whose changes turn out to be only visual goes back to the person without
        // the full review. Never on a gate that has not passed: no review launders a failure. And
        // never when it was built to a picked design: then the question is not only whether it
        // restyled and nothing more, but whether it looks like the picture, and only a review asks.
        if (state.getPolishBase() != null && state.getPolishDesign() != null) {
            state.setPolishCheck("The polish was built to the design \"" + state.getPolishDesign()
                    + "\", so it was reviewed in full and compared with that picture.");
        } else if (state.getPolishBase() != null && gateHasPassed() && new PolishScope(run).skipsReview()) {
            state.setLastError(null);
            state.setPhase(Phase.DONE);
            return;
        }
        ReviewRound round = round();
        Review review = firstUsableReview(
                run.workspace().git().reviewDiff(state.getBaselineCommit(), "HEAD", REVIEW_DIFF_CAP),
                round);

        // A reviewer that never ran is not a clean review. Accepting on its silence would mean
        // every project built while the other vendor is out of quota ships unreviewed -- which is
        // precisely what happened before this check existed.
        if (review.isUnavailable()) {
            state.setResumePhase(Phase.REVIEWING);
            state.setPhase(Phase.SUSPENDED);
            state.setRetryAfter(Suspension.orUnstatedWait(review.getRetryAfter()));
            state.setLastError("waiting to be reviewed: " + review.getParseError());
            if (review.getRetryAfter() == null) {
                // A quota refusal names its reset and is worth waiting for. One that names nothing
                // is a reviewer logged out, a broken tool, or nobody installed to ask — each of which
                // the next attempt meets again, so a few of them in a row go to a person.
                PersonHold.failedCall(state, "no reviewer could be reached: " + review.getParseError());
            }
            return;
        }
        state.setFailedCallsInARow(0);
        // A full review has read everything since the polish too, so there is nothing left to scope.
        state.setPolishBase(null);

        // The reviewer has now answered, so whatever it said last time it could not is history.
        // Left in place, "reviewer unavailable: RATE_LIMITED" stays on the card for ever and reads
        // as current: a project that has in fact been reviewed looks like one that is still stuck,
        // and the vendor looks out of quota long after it recovered.
        state.setLastError(null);

        // A review is a second opinion, not a verdict. A project whose gate never passed can be
        // sent here deliberately — asking another model why it is stuck is a reasonable thing to
        // want — and it goes back where it came from afterwards, however clean the review was.
        //
        // Without this, the Review button laundered a failure into a finish: one project on this
        // machine parked with its acceptance still failing, was sent for a cross-review, and came
        // out DONE with the failing command still recorded beside it. Nothing had been rebuilt and
        // nothing had been re-run. That is the one thing Forge exists to make impossible.
        if (notCompared(review)) {
            // A polish built to a picture is finished only when someone has held it up to the
            // picture. Nobody did, so the person is asked instead of being told it was checked.
            state.setPhase(Phase.REVIEWING);
            PersonHold.hold(state, "no reviewer compared the result with the design \""
                    + state.getPolishDesign() + "\" it was built to; compare artifacts/polish-after.png with "
                    + "the design yourself, or press Start to ask again");
            return;
        }
        if (review.getParseError() != null && round.rechecking()) {
            // Not a verdict on anything. The findings it was meant to recheck are still owed an
            // answer, and finishing the project on a reply nobody could read would be the one way
            // left to get past a blocking finding without fixing it.
            state.setPhase(Phase.REVIEWING);
            PersonHold.hold(state, "the reviewer's reply could not be read, so nobody knows whether "
                    + "the blocking findings were fixed (" + review.getParseError() + ")");
            return;
        }
        List<Finding> standing = ReviewLoop.outstanding(round.previous(), review);
        // Counted, not judged: a note on each source file too long to be one job, for the person to
        // send back or not. Never blocking, so it changes nothing about what is standing.
        List<Finding> findings = new ArrayList<>(review.getFindings());
        findings.addAll(LongFiles.notes(run.paths().repo(), run.workspace().git().files()));
        review.setFindings(findings);
        ReviewStore.save(run.paths().reviewFile(), review);
        state.setBlockingFindings(standing);

        if (!gateHasPassed()) {
            state.setLastError("reviewed, but not accepted: "
                    + state.getLastFailure().command() + " is still failing");
            state.setPhase(Phase.PARKED);
            return;
        }
        if (standing.isEmpty()) {
            // Settled: later reviews of this project are not about the picture any more. Kept
            // until here so every review in a repair loop compares with it.
            state.setPolishDesign(null);
            state.setPhase(Phase.DONE);
            return;
        }
        ReviewLoop.sendBackOrHold(state, standing);
    }

    /** Whether a review owed a comparison with a picked design went without one. */
    private boolean notCompared(Review review) {
        return run.state().getPolishDesign() != null && !Boolean.TRUE.equals(review.getComparedWithDesign());
    }

    /**
     * What this review must check besides the change: the findings the agent was last sent to fix.
     *
     * <p>Those are whatever blocking findings the state still holds — after a repair turn, or after
     * a person sent some back by hand. A fresh review asked for from the dashboard clears them
     * first. Findings recorded before they carried ids are given theirs here, from the same text
     * the review form keys them on.
     */
    private ReviewRound round() {
        RunState state = run.state();
        List<Finding> previous = state.getBlockingFindings() == null
                ? new ArrayList<>() : new ArrayList<>(state.getBlockingFindings());
        for (Finding finding : previous) {
            if (finding.getId() == null || finding.getId().isBlank()) {
                finding.setId(ReviewItem.idOf(finding));
            }
        }
        Map<String, String> answers = new HashMap<>();
        Map<String, List<String>> defences = new LinkedHashMap<>();
        if (state.getPlan() != null && state.getPlan().getReviewFindings() != null) {
            for (FindingAnswer answer : state.getPlan().getReviewFindings()) {
                answers.put(answer.getId(), answer.said());
                defences.putAll(defence(answer));
            }
        }
        List<String> ruledFine = ReviewHistory.ruledFine(
                ReviewHistory.load(run.paths().reviewHistoryFile()));
        return new ReviewRound(previous, answers, ruledFine, filesOf(previous, defences));
    }

    /**
     * The file holding the test a builder disputed a finding with, and the test's name to show it
     * around.
     *
     * <p>A dispute is the builder defending its code with a test, and the reviewer rules on it. It
     * was told "the next reviewer reads your test" and was never shown it: the recheck held only the
     * files the findings named, and the diff is cut before a late test file. A defence nobody reads
     * loses by default. {@code e2e/market.spec.ts > sells}, {@code src/FooTest.java::bar} and
     * {@code src/a.test.ts:42} all name a file and, after it, the test.
     */
    private static Map<String, List<String>> defence(FindingAnswer answer) {
        String test = answer.getTest() == null ? "" : answer.getTest().strip();
        if (!"disputed".equalsIgnoreCase(answer.getAction()) || test.isEmpty()) {
            return Map.of();
        }
        String[] parts = test.split("::|#| > ", 2);
        String file = parts[0].strip().replaceFirst(":\\d+$", "");
        String name = parts.length > 1 ? parts[1].strip() : "";
        return file.isEmpty() ? Map.of() : Map.of(file, List.of(name));
    }

    /** The most of one finding's file a recheck is shown, and of all of them together. */
    private static final int FILE_CAP = 30_000;
    private static final int FILES_CAP = 90_000;

    /**
     * The files the findings to recheck are in, as they stand now.
     *
     * <p>The review diff runs from the start of the project and is cut at {@link #REVIEW_DIFF_CAP},
     * so on a large project the file a fix went into is often past the cut, and a reviewer told to
     * decide "from the diff" ruled real fixes still present. Read from the repository rather than
     * the diff, and capped, because the reviewer's quota is the scarce one. A file over the cap is
     * shown around what its findings name ({@link FileExcerpt}), not from the top. A path that is
     * missing or leads outside the repository is left out.
     */
    private Map<String, String> filesOf(List<Finding> findings, Map<String, List<String>> defences) {
        Path repo = run.paths().repo().normalize();
        Map<String, List<String>> about = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String name = finding.getFile();
            if (name != null && !name.isBlank()) {
                List<String> words = about.computeIfAbsent(name, n -> new ArrayList<>());
                words.add(finding.getSummary());
                words.add(finding.getDetail());
                words.add(finding.getStillPresent());
            }
        }
        defences.forEach((file, words) -> about.computeIfAbsent(file, n -> new ArrayList<>()).addAll(words));
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<String>> entry : about.entrySet()) {
            Path file = repo.resolve(entry.getKey()).normalize();
            if (total >= FILES_CAP || !file.startsWith(repo) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String shown = FileExcerpt.of(Files.readString(file), Math.min(FILE_CAP, FILES_CAP - total),
                        entry.getValue());
                files.put(entry.getKey(), shown);
                total += shown.length();
            } catch (IOException | RuntimeException e) {
                // Not a text file this can read; the recheck falls back on the diff for this one.
            }
        }
        return files;
    }

    /**
     * Asks every possible reviewer in turn, rather than the one that happens not to be the author.
     *
     * <p>The reviewing vendor is by definition the one that did not build this, so it is out of
     * quota exactly when the builder finishes — routinely, not rarely. A project here sat for a day
     * and a half saying "reviewer unavailable: RATE_LIMITED" while another vendor able to read it
     * was idle. The last refusal is what gets reported, so the message still names a real reason
     * when nobody could be reached.
     *
     * <p>A reply that could not be read moves on too. It has no findings, so it counted as clean:
     * the loop stopped at it and the project went to DONE with nobody's opinion behind it, while a
     * reviewer that could have answered was never asked. It is kept, and reported, only when
     * nobody after it produced anything better.
     */
    private Review firstUsableReview(String diff, ReviewRound round) {
        RunPaths paths = run.paths();
        List<AgentKind> reviewers = run.agents().reviewersFor(run.state().getAssignedTo());
        // The cheapest models read for each claimed feature first, each reviewer taking what the one
        // before left unanswered; then one best model reviews the change, holding what they could
        // not find.
        List<FeatureCheck> checks = featureChecks(reviewers);
        Review review = null;
        Review unreadable = null;
        for (AgentKind kind : reviewers) {
            Agent reviewer = run.agents().get(kind);
            review = new ReviewStage(reviewer, paths.repo(), paths.logs(), paths.reviewFile(),
                    run.models().bigReview(kind))
                    .review(run.spec(), diff, checks, round, run.state().getPolishDesign());
            if (review.isUnavailable()) {
                continue;
            }
            if (review.getParseError() == null && notCompared(review)) {
                // As good as unread for this purpose: the next reviewer may manage what this one did not.
                review.setParseError("did not compare the result with the design \""
                        + run.state().getPolishDesign() + "\" it was built to");
                ReviewStore.save(paths.reviewFile(), review);
            }
            if (review.getParseError() == null && !review.unexplainedRulings().isEmpty()) {
                // A ruling nobody can check is no ruling: ask the next reviewer, and if none says
                // why, the recheck is unread and a person decides.
                review.setParseError("let " + String.join(", ", review.unexplainedRulings())
                        + " go without saying where the fix is or why the finding was wrong");
                ReviewStore.save(paths.reviewFile(), review);
            }
            if (review.getParseError() == null) {
                break;
            }
            unreadable = review;
        }
        if (unreadable != null && review != unreadable
                && (review.isUnavailable() || review.getParseError() != null)) {
            // Each attempt wrote review.json; put back the one that did run.
            review = unreadable;
            ReviewStore.save(paths.reviewFile(), review);
        }
        if (review == null) {
            // Nothing is installed but the builder. Not a failure of the project, and not
            // something another turn would fix, so it is reported rather than repaired.
            review = Review.unavailable("no other model is installed, so nothing can review this", null);
        }
        return review;
    }

    /** The cheap pass over every claimed feature, across the reviewers in the order they are asked. */
    private List<FeatureCheck> featureChecks(List<AgentKind> reviewers) {
        List<PlanFeature> claimed = FeatureReview.claimedBy(run.state().getPlan());
        if (claimed.isEmpty()) {
            return List.of();
        }
        RunPaths paths = run.paths();
        List<FeatureReview> passes = reviewers.stream()
                .map(kind -> new FeatureReview(run.agents().get(kind), paths.repo(), paths.logs(),
                        run.models().featureReview(kind)))
                .toList();
        return new FeaturePass(passes).run(run.spec(), claimed);
    }

    /**
     * Whether the last gate run passed.
     *
     * <p>Read off {@code lastFailure}, which {@link GateStep} clears on a pass and sets on a
     * failure, and which is the only thing that writes it. A project that has never gated has none
     * either, and cannot reach a review without going through the gate first.
     */
    private boolean gateHasPassed() {
        return run.state().getLastFailure() == null;
    }
}
