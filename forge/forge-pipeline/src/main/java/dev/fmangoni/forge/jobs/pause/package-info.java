/**
 * Stop with a reason: holding a project until a person presses Resume Now, and saying why.
 *
 * <p><b>Belongs here:</b> {@code Pausing} — stopping a project, or holding one that is not running,
 * and lifting the hold when Resume Now is pressed — and {@code PauseNote}, the reason, the time and
 * the day to be reminded of it, kept beside the run's state.
 *
 * <p><b>Not here:</b> Stopping without a reason ({@code JobPool.stop}), and the cool-off itself,
 * which every runner already waits out ({@code jobs.work.Eligibility}).
 *
 * <p><b>Start with:</b> {@code Pausing}.
 *
 * <p>Depends on: git, jobs.work, json, project, spec, spec.model, state.
 */
package dev.fmangoni.forge.jobs.pause;
