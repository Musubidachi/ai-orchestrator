/**
 * The layer that is a model rather than a scanner: another model reads the change for
 * vulnerabilities, with the scanners' results in front of it.
 *
 * <p><b>Belongs here:</b> The review turn and its prompt.
 *
 * <p><b>Not here:</b> The repair turn a serious finding earns, which belongs to the pipeline.
 *
 * <p><b>Start with:</b> {@code SecurityReview.run}, {@code SecurityBrief}.
 *
 * <p>Depends on: agent, agent.model, json, security.model, spec.model.
 */
package dev.fmangoni.forge.security.ai;
