package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.spec.model.Spec;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * The opening prompt for a change request against an existing project.
 *
 * <p>Separate from {@link BuildBrief} because the risk is different. Asked to build, an agent
 * starts from nothing and the worst case is that it builds too little. Asked to improve, it arrives
 * at working code it did not write, and the worst case is that it rewrites what already worked to
 * suit its own preferences — passing the new checks while quietly discarding the old behaviour.
 */
@UtilityClass
public class ImproveBrief {

    public String of(Spec spec, Duration cap, Plan previous) {
        return """
                Improve the project in this repository. The change requested is in IMPROVEMENT.md.

                Read SPEC.md first — it describes what this project was originally built to do — \
                then IMPROVEMENT.md for what is being asked of you now. AGENTS.md (identical to \
                CLAUDE.md) carries the standing rules.

                This is existing, working code:

                - The original acceptance commands are re-run alongside the new ones. A change that \
                delivers the new behaviour by breaking the old one is not accepted, and the \
                orchestrator will catch it.
                - Change what the request asks for and leave the rest alone. Restructuring code you \
                were not asked to touch makes the change impossible to review and risks a \
                regression for no benefit.
                - Where the existing approach differs from how you would have done it, that is not \
                itself a reason to change it. If it genuinely blocks the improvement, say so in \
                your handoff rather than quietly rewriting around it.

                Improvement: %s

                %s%s""".formatted(spec.title(), TimeContract.resuming(previous), TimeContract.of(cap));
    }
}
