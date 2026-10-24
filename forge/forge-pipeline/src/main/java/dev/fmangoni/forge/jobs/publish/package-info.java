/**
 * Publishing to GitHub as a job: when a version is approved, and when a person presses Publish.
 *
 * <p><b>Belongs here:</b> {@code PublishJob}, which checks it may run and hands the work to
 * forge-deploy's {@code Publisher} on a worker, under the repository's lock.
 *
 * <p><b>Not here:</b> What is pushed, where, or how: that is {@code deploy.github}'s. Publishing on
 * any schedule or retry of its own.
 *
 * <p><b>Start with:</b> {@code PublishJob.start}.
 *
 * <p>Depends on: deploy.github, jobs.button, jobs.stop, jobs.work, project.
 */
package dev.fmangoni.forge.jobs.publish;
