/**
 * Driving one project through its phases, persisting after every transition.
 *
 * <p><b>Belongs here:</b> {@code Orchestrator} (the loop and the dispatch, and nothing else) and
 * {@code Recovery}, which puts a run back in order before its next step.
 *
 * <p><b>Not here:</b> The phases ({@code pipeline.step}), what a building turn involves ({@code
 * pipeline.turn}) and the run every step is handed ({@code pipeline.run}). Choosing which project
 * runs is {@code jobs}.
 *
 * <p><b>Start with:</b> {@code Orchestrator.advance}.
 *
 * <p>Depends on: agent, agent.model, pipeline.run, pipeline.step, pipeline.turn, project, spec, spec.model, spec.tier, state.
 */
package dev.fmangoni.forge.pipeline;
