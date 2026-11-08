/**
 * Driving one project through its phases, persisting after every transition.
 *
 * <p><b>Belongs here:</b> {@code Outline}, every step of a run in order and the one place to attach
 * to it; {@code Orchestrator}, the loop that follows it and nothing else; and what the outline does
 * before any step: {@code Recovery}, which puts a run back in order, {@code MissingPictures},
 * which holds a run whose spec names pictures that are not there, and {@code PictureLook}, which
 * asks the person whether the screens came out like the spec's pictures (again after each gate).
 *
 * <p><b>Not here:</b> The phases ({@code pipeline.step}), what a building turn involves ({@code
 * pipeline.turn}) and the run every step is handed ({@code pipeline.run}). Choosing which project
 * runs is {@code jobs}.
 *
 * <p><b>Start with:</b> {@code Outline.of}, then {@code Orchestrator.advance}.
 *
 * <p>Depends on: agent, agent.model, gate, gate.words, git, pipeline.run, pipeline.step, pipeline.turn, project, spec, spec.model, spec.tier, state, status.gate.
 */
package dev.fmangoni.forge.pipeline;
