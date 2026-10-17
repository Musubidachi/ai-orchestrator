/**
 * What a building turn involves: which prompt it sends, running it, and what is kept afterwards.
 *
 * <p><b>Belongs here:</b> Choosing the brief for each kind of turn, running the agent and handling
 * the outcome, the vendor conversation, the handoff and its digest, and whether to compact.
 *
 * <p><b>Not here:</b> The phases themselves. A rejected or empty call is not a session, and that
 * rule lives here.
 *
 * <p><b>Start with:</b> {@code Turns}, {@code AgentTurn.run}.
 *
 * <p>Depends on: agent.model, agent.stream, brief, brief.repair, feature, pipeline.run, plan, project, security.model, spec.model, state.
 */
package dev.fmangoni.forge.pipeline.turn;
