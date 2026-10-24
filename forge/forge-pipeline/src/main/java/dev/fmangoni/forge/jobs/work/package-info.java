/**
 * The machinery every kind of job shares: the workers in flight, and the project one is about.
 *
 * <p><b>Belongs here:</b> The pool and its worker, looking a project up (its spec, its wishlist and
 * its paths), the short hold on a repository another Forge process is in, and rewriting a report
 * when a job ends.
 *
 * <p><b>Not here:</b> Any decision about what should run. This package is below all of them so that
 * none of the sub-packages has to point back up at the facade.
 *
 * <p><b>Start with:</b> {@code JobPool.submit}, {@code Projects.find}.
 *
 * <p>Depends on: agent, agent.ledger, agent.model, agent.policy, deploy.github, deploy.shelf, process, project, report, review, spec, spec.model, state.
 */
package dev.fmangoni.forge.jobs.work;
