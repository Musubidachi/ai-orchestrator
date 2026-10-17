/**
 * Which projects are running now: the facade the dashboard talks to, and the pool they run in.
 *
 * <p><b>Belongs here:</b> {@code JobRunner} — a thin facade and the one lock — and the dashboard's
 * rows.
 *
 * <p><b>Not here:</b> Everything it delegates to: the machinery jobs share ({@code jobs.work}),
 * what may start and the launch ({@code jobs.queue}), what a button starts ({@code jobs.button}),
 * what a submission sets off ({@code jobs.submit}), the unattended run ({@code jobs.unattended})
 * and stopping safely ({@code jobs.stop}).
 *
 * <p><b>Start with:</b> {@code JobRunner}.
 *
 * <p>Depends on: agent, agent.ledger, jobs.button, jobs.polish, jobs.queue, jobs.stop, jobs.submit, jobs.work, project, report.deck, review.decision.model, spec, spec.model, state, status.
 */
package dev.fmangoni.forge.jobs;
