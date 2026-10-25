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

                %s%s%s""".formatted(spec.title(), owned(spec), TimeContract.resuming(previous), TimeContract.of(cap));
    }

    /**
     * The folders this version may change, said before it starts rather than learned from the gate.
     *
     * <p>Empty when the spec owns nothing, which is every project not built one feature per version.
     */
    String owned(Spec spec) {
        if (spec.owns().isEmpty()) {
            return "";
        }
        StringBuilder folders = new StringBuilder();
        for (String glob : spec.owns()) {
            folders.append("- ").append(glob).append('\n');
        }
        return """
                This version owns these folders, and may change nothing outside them:

                %s
                The gate checks every file changed since this version began, and fails it for any \
                file outside them, however small the change. Other features own the rest of the \
                code, shared parts included. If this feature cannot be built without changing a file \
                outside its folders, do not change it: stop, and say in your handoff which file and \
                why, so the version that owns it can be asked.

                """.formatted(folders);
    }
}
