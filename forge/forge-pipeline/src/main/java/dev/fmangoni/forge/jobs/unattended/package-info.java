/**
 * What {@code forge run} does without anyone watching: advance every eligible project, and reclaim
 * disk from finished ones.
 *
 * <p><b>Belongs here:</b> The scheduler, the job it schedules, and pruning.
 *
 * <p><b>Not here:</b> Anything the dashboard drives. Pruning deletes files, so it is only ever an
 * explicit command.
 *
 * <p><b>Start with:</b> {@code Scheduler.run}, {@code Prune.run}.
 *
 * <p>Depends on: agent, agent.ledger, agent.policy, git, jobs.work, pipeline, pipeline.run, project, report, review, spec, spec.model, spec.rank, spec.tier, state.
 */
package dev.fmangoni.forge.jobs.unattended;
