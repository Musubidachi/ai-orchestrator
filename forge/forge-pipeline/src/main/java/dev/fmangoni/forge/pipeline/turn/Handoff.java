package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanParser;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import lombok.experimental.UtilityClass;

/** Keeps the agent's handoff for the next session, and its digest where a person can read it. */
@UtilityClass
class Handoff {

    /**
     * Stores the plan from the agent's closing message.
     *
     * <p>An unusable handoff leaves the previous one in place rather than clearing it: a turn that
     * ended badly is exactly when the last good plan is worth most.
     */
    void record(RunPaths paths, RunState state, AgentResult result) {
        Plan plan = PlanParser.parse(result.finalMessage());
        if (plan.usable()) {
            state.setPlan(plan);
            writeDigest(paths, plan);
        }
    }

    /**
     * Keeps the digest beside the report.
     *
     * <p>It is also the honest answer to "what does the model think it built", written without the
     * pressure of a gate to satisfy — which makes it worth reading even when nothing is compacted.
     */
    private void writeDigest(RunPaths paths, Plan plan) {
        if (plan.getDigest() == null || plan.getDigest().isBlank()) {
            return;
        }
        try {
            Files.writeString(paths.digestFile(), plan.getDigest(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A digest that cannot be written is not worth stopping a build for; the copy in
            // state.json is the one the next turn actually reads.
            System.err.println("could not write digest: " + e.getMessage());
        }
    }
}
