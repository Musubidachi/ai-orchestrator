package dev.fmangoni.forge.pipeline.run;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelChoice;
import dev.fmangoni.forge.state.RunState;
import java.util.List;

/**
 * One project's run, as every step of the pipeline sees it.
 *
 * <p>{@code state} is the one mutable part: every step reads its inputs from it and records its
 * outcome in it, and {@link Orchestrator} saves it after each step.
 *
 * @param wishlist resolves an improvement's ancestry, and with it the full set of commands it must satisfy
 * @param models   which model does which job on this project
 */
public record RunContext(Spec spec, RunPaths paths, RunState state, Agents agents, Workspace workspace,
                  Wishlist wishlist, ModelChoice models) {

    /**
     * Everything this spec must satisfy, its ancestors' commands included.
     *
     * <p>Resolved here rather than baked into the spec so that editing a parent's acceptance
     * immediately governs its improvements too, with no duplication to keep in step.
     */
    public List<String> acceptance() {
        return wishlist.effectiveAcceptance(spec);
    }

    public List<String> evidence() {
        return wishlist.effectiveEvidence(spec);
    }
}
