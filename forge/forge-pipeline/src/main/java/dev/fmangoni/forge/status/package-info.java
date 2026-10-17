/**
 * Everything the dashboard shows about one project, assembled from its files on each poll.
 *
 * <p><b>Belongs here:</b> {@code JobStatus}, which puts the six groups together and nothing else.
 *
 * <p><b>Not here:</b> The groups ({@code status.model}) and what they read ({@code status.read}).
 * Nothing here decides anything: the reasons it shows are worked out in {@code jobs} and {@code
 * deploy} and passed in.
 *
 * <p><b>Start with:</b> {@code JobStatus.of}, {@code JobStatus.queued}.
 *
 * <p>Depends on: project, state, status.model.
 */
package dev.fmangoni.forge.status;
