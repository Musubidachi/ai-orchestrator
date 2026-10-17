/**
 * The security check: every layer over the project at HEAD, kept as one report per commit.
 *
 * <p><b>Belongs here:</b> {@code SecurityCheck}, and nothing else: it runs each layer guarded, so
 * one failure cannot sink the others, and decides whether what came back earns the builder a repair
 * turn.
 *
 * <p><b>Not here:</b> The layers themselves ({@code security.layer}), the advisory database ({@code
 * security.osv}), the AI reading ({@code security.ai}) and the report's own data ({@code
 * security.model}).
 *
 * <p><b>Start with:</b> {@code SecurityCheck.run}.
 *
 * <p>Depends on: agent, agent.model, git, process.local, project, security.ai, security.layer, security.model, security.osv, spec.model.
 */
package dev.fmangoni.forge.security;
