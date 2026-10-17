/**
 * The jobs a person starts by pressing something: deploy, marketing kit, security rescan, reopen,
 * re-review, and Play.
 *
 * <p><b>Belongs here:</b> One class per button, and the wrapper that gives each one a worker slot,
 * the repository lock and a rewritten report.
 *
 * <p><b>Not here:</b> Anything the queue may start on its own. Nothing here ever runs unattended.
 *
 * <p><b>Start with:</b> {@code DeployJob.start}, {@code MarketingJob.start}, {@code
 * SecurityRescan.start}, {@code Reopening}.
 *
 * <p>Depends on: agent, deploy, gate, git, jobs.queue, jobs.stop, jobs.work, marketing, pipeline.run, pipeline.step, project, review, review.decision.model, review.model, security, security.osv, spec.model, state, walkthrough.
 */
package dev.fmangoni.forge.jobs.button;
