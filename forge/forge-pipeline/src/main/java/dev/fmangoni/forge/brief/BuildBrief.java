package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.spec.model.Spec;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * The building prompt: implement what the spec describes, within a stated time budget.
 *
 * <p>Kept short. The substance lives in {@code SPEC.md} and the house rules already written into
 * the workspace, and repeating them here would give the agent two copies that can drift apart
 * mid-run.
 */
@UtilityClass
public class BuildBrief {

    /**
     * @param cap      wall clock for this turn
     * @param previous the last session's handoff, or {@code null} on the first turn
     */
    public String of(Spec spec, Duration cap, Plan previous) {
        boolean resuming = previous != null && previous.usable();

        return """
                %s the project described in SPEC.md, in this repository.

                Read SPEC.md and AGENTS.md (identical to CLAUDE.md) before starting. SPEC.md lists \
                the acceptance commands that decide whether this project is done. They are run by \
                the orchestrator, in a separate process, against the files you leave on disk — your \
                own report of success is not consulted.

                1. Implement what the spec asks for, and only that.
                2. Run the acceptance commands yourself and get them passing.
                3. Produce any artefacts listed under Evidence.

                Do not weaken the acceptance commands or the assertions inside the tests they run. \
                If an acceptance command is genuinely wrong or impossible, stop and say so plainly \
                rather than working around it.

                Project: %s

                %s%s""".formatted(
                resuming ? "Continue building" : "Build",
                spec.title(),
                TimeContract.resuming(previous),
                TimeContract.of(cap));
    }
}
