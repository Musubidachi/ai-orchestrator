/**
 * What may start, in what order, and why not.
 *
 * <p><b>Belongs here:</b> Auto-run and the review-first ordering, the reasons a project cannot
 * start, the eligibility both schedulers share, choosing a vendor for a new project, and the short
 * hold on a repository another Forge process is in.
 *
 * <p><b>Not here:</b> Starting anything. The queue decides; {@code jobs} launches.
 *
 * <p><b>Start with:</b> {@code JobQueue.eligible}, {@code StartRules.blockedReason}, {@code
 * Eligibility}.
 *
 * <p>Depends on: agent, agent.ledger, agent.ledger.model, agent.model, agent.policy, git, jobs.stop, jobs.work, pipeline, pipeline.run, project, spec, spec.model, spec.rank, spec.tier, state.
 */
package dev.fmangoni.forge.jobs.queue;
