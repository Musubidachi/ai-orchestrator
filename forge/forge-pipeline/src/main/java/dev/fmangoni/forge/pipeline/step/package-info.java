/**
 * One class per phase the pipeline can be in.
 *
 * <p><b>Belongs here:</b> Scaffolding, planning, gating, the security check and the cross-review,
 * each taking the run context and recording its outcome in the state.
 *
 * <p><b>Not here:</b> The loop and the dispatch, which is {@code Orchestrator}, and the agent turns
 * themselves.
 *
 * <p><b>Start with:</b> {@code ScaffoldStep}, {@code PlanStep}, {@code GateStep}, {@code
 * SecurityStep}, {@code ReviewStep}.
 *
 * <p>Depends on: agent.model, brief, deploy.github, feature, gate, pipeline.run, pipeline.turn, plan, project, review, review.decision, review.decision.model, review.feature, review.model, security, security.model, security.osv, spec.model, state.
 */
package dev.fmangoni.forge.pipeline.step;
