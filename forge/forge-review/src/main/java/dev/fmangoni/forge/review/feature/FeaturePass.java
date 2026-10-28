package dev.fmangoni.forge.review.feature;

import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * The cheap pass across every reviewer that may be asked, in order.
 *
 * <p>The first reviewer is asked about every claim. A claim it did not answer — it was refused,
 * ended without a word, or replied in something other than JSON — goes to the next reviewer, and so
 * on until every claim has a verdict or nobody is left. Before this, one reviewer's silence was the
 * end of it: 003.3 reached its big review with three of four claims unchecked because Antigravity
 * ended each of those sessions after a refused command, while Codex was idle.
 *
 * <p>Only the first {@link FeatureReview#MAX_FEATURES} claims are ever passed on. The rest were
 * never going to be checked, and handing them on would buy the calls the bound exists to save.
 */
@RequiredArgsConstructor
public final class FeaturePass {

    /** Each possible reviewer's cheap pass, in the order they are to be asked. */
    private final List<FeatureReview> reviewers;

    public List<FeatureCheck> run(Spec spec, List<PlanFeature> claimed) {
        List<PlanFeature> checkable = claimed.subList(0, Math.min(claimed.size(), FeatureReview.MAX_FEATURES));
        Map<String, FeatureCheck> byName = new LinkedHashMap<>();
        for (FeatureReview reviewer : reviewers) {
            List<PlanFeature> owed = checkable.stream()
                    .filter(feature -> !answered(byName.get(feature.getName())))
                    .toList();
            if (owed.isEmpty()) {
                break;
            }
            for (FeatureCheck check : reviewer.run(spec, owed)) {
                if (check.answered() || !byName.containsKey(check.getFeature())) {
                    byName.put(check.getFeature(), check);
                }
            }
        }
        List<FeatureCheck> checks = new ArrayList<>();
        for (int i = 0; i < claimed.size(); i++) {
            String name = claimed.get(i).getName();
            FeatureCheck check = byName.get(name);
            if (check == null) {
                check = FeatureCheck.unchecked(name, i < checkable.size()
                        ? "not checked: no reviewer could be asked"
                        : "not checked: only the first " + FeatureReview.MAX_FEATURES + " claims are");
            }
            checks.add(check);
        }
        return checks;
    }

    private static boolean answered(FeatureCheck check) {
        return check != null && check.answered();
    }
}
