package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.review.feature.ScopeCheck;
import dev.fmangoni.forge.review.feature.ScopeVerdict;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How much reviewing a visual polish needs, decided from what it changed rather than from what it
 * was asked to do.
 *
 * <p>Reading the whole project again on the reviewer's best model, to confirm that a restyle only
 * restyled, spends the scarcest quota there is on the question least likely to find anything. So a
 * polish whose changes are all stylesheets, pictures, fonts and documents is not cross-reviewed
 * again. One that touched code is put to the reviewer's cheapest model as a single question — did
 * this change anything besides appearance — and only a "yes" or no answer at all sends it to the
 * full review. The gate and the security check have run either way, and the person still decides.
 */
final class PolishScope {

    /** Kinds of file whose change cannot alter what a program does. */
    private static final Set<String> APPEARANCE = Set.of("css", "scss", "sass", "less",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "bmp",
            "woff", "woff2", "ttf", "otf", "eot", "md", "txt");

    /** Evidence, recordings and the chosen design page: written for the person, not run. */
    private static final String EVIDENCE = "artifacts/";

    private final RunContext run;

    PolishScope(RunContext run) {
        this.run = run;
    }

    /**
     * Whether the full cross-review can be skipped, with why recorded on the state either way.
     *
     * <p>When no reviewer could be asked at all, nothing is decided and the polish's starting point
     * is kept, so the review that follows — which will wait for quota like any other — asks again.
     */
    boolean skipsReview() {
        RunState state = run.state();
        Optional<List<String>> changed = run.workspace().git().changedFiles(state.getPolishBase(), "HEAD");
        if (changed.isEmpty()) {
            decided("its changes could not be listed, so it was reviewed in full");
            return false;
        }
        if (changed.get().stream().filter(file -> !file.startsWith(EVIDENCE)).allMatch(PolishScope::appearance)) {
            decided("it changed only stylesheets, images, fonts and documents, so it was not cross-reviewed again");
            return true;
        }

        String diff = run.workspace().git().reviewDiff(state.getPolishBase(), "HEAD", ReviewStep.REVIEW_DIFF_CAP);
        for (AgentKind kind : run.agents().reviewersFor(state.getAssignedTo())) {
            ScopeVerdict verdict = new ScopeCheck(run.agents().get(kind), run.paths().repo(), run.paths().logs(),
                    run.models().featureReview(kind)).run(run.spec().title(), diff);
            if (!verdict.reached()) {
                continue;
            }
            if (verdict.appearanceOnly()) {
                decided("it changed code, which " + kind + "'s cheapest model read as appearance only ("
                        + verdict.why() + "), so it was not cross-reviewed again");
                return true;
            }
            decided("it changed more than appearance (" + verdict.why() + "), so it was reviewed in full");
            return false;
        }
        return false;
    }

    private void decided(String how) {
        run.state().setPolishCheck("The polish " + how + ".");
        run.state().setPolishBase(null);
    }

    private static boolean appearance(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && APPEARANCE.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
