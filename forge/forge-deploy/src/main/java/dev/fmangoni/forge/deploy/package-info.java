/**
 * Publishing an accepted project with the deploy command its spec names, only when a person presses
 * Deploy.
 *
 * <p><b>Belongs here:</b> The preconditions (accepted, gate passed, security ruled on, tools
 * installed), running the command, the health check, and the deploy history.
 *
 * <p><b>Not here:</b> Logging in, holding credentials or installing tools: Forge never does these.
 * Nothing here runs on its own schedule.
 *
 * <p><b>Start with:</b> {@code Deployer.blockedReason}, {@code Deployer.deploy}, {@code
 * Deployment}.
 *
 * <p>Depends on: git, json, process, process.local, project, review.decision, review.decision.model, security.model, spec.model, state.
 */
package dev.fmangoni.forge.deploy;
