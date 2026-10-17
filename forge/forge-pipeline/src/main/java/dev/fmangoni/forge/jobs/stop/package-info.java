/**
 * Stopping Forge without destroying a turn.
 *
 * <p><b>Belongs here:</b> Draining, waiting for the agent calls that are in flight, and reporting
 * what is still working.
 *
 * <p><b>Not here:</b> Killing anything. If the wait runs out it says so and leaves the turn alone —
 * that judgement belongs to whoever is at the keyboard.
 *
 * <p><b>Start with:</b> {@code Shutdown.quiesce}, {@code Quiesce}.
 *
 * <p>Depends on: jobs.work, project, spec, spec.model, state.
 */
package dev.fmangoni.forge.jobs.stop;
