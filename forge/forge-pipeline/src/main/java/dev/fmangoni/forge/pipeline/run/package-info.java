/**
 * The run every step of the pipeline is working on, and the workspace it works in.
 *
 * <p><b>Belongs here:</b> {@code RunContext} — the spec, the paths, the state, the agents, the
 * workspace, the wishlist and the model choice — {@code Workspace}, which prepares the
 * repository and rewrites the house rules before every turn (only the sections that apply, through
 * {@code RuleSections}, with the skills beside them through {@code SkillInstall}), and the two ways a run stops without
 * a verdict: {@code Suspension} after a vendor refused a call, and {@code PersonHold} when trying
 * again could only repeat what just happened. And {@code ReviewLoop}, which decides what a review's
 * blocking findings mean — sent back, or a person's call — and which the review step and the repair
 * turn both read.
 *
 * <p><b>Not here:</b> Any step's own work. Below the steps rather than beside {@code Orchestrator},
 * so that a step and the loop that dispatches it do not point at each other — and below the job
 * queue, which holds a project for a person when Forge itself keeps failing on it.
 *
 * <p><b>Start with:</b> {@code RunContext}, {@code Workspace.refreshRules}, {@code Suspension.after}.
 *
 * <p>Depends on: agent, agent.model, feature, git, project, review.decision.model, review.model, spec, spec.model, spec.tier, state.
 */
package dev.fmangoni.forge.pipeline.run;
