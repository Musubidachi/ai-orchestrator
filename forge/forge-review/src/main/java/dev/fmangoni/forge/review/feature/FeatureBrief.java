package dev.fmangoni.forge.review.feature;

import dev.fmangoni.forge.plan.PlanFeature;
import lombok.experimental.UtilityClass;

/**
 * The cheap pass's prompt: one claimed feature, one question.
 *
 * <p>Deliberately narrow. This runs on the smallest model the vendor has, once per feature, and a
 * small model does far better at "find where this happens and say whether it really does" than at
 * judging a whole change. The reading it produces is then handed to the one expensive review as
 * leads, which is what makes that review cheaper than it used to be: it arrives knowing which
 * claims are already in doubt.
 */
@UtilityClass
class FeatureBrief {

    String of(PlanFeature feature, String specTitle) {
        return """
                In this repository, check one claim and answer in JSON.

                The project "%s" says it has this feature:

                  Feature: %s
                  What it should show: %s

                Read the code that would implement it. You may open files; do not change anything \
                and do not run commands — this session is read-only, and refused tool calls are \
                expected.

                Answer only this question: is the feature really there, in the code, doing what the \
                claim says? Not whether it is written well, and not whether you would have built it \
                differently.

                Reply with exactly this shape and nothing else:

                {"verdict":"holds","why":"one line, naming the file that does it"}

                Use "holds" when you found the code that does it. Use "unproven" when you could not \
                find it, when what you found does something materially different, or when it is \
                only a stub, a placeholder or a hard-coded value pretending to be the real thing. \
                In either case name the file you looked at in "why", so the next reader can start \
                where you stopped.
                """.formatted(specTitle, feature.getName(), blankIfNull(feature.getDemo()));
    }

    private String blankIfNull(String demo) {
        return demo == null || demo.isBlank() ? "(the handoff did not say)" : demo;
    }
}
