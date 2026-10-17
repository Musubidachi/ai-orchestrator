/**
 * The handoff an agent writes at the end of a turn: what it finished, what is left, and what it
 * proposes.
 *
 * <p><b>Belongs here:</b> {@code Plan}, its steps, proposed features and answers to review findings,
 * and {@code PlanParser}, which pulls it out of the agent's closing message.
 *
 * <p><b>Not here:</b> What a person approved. Approved features are {@code feature.Feature}, which
 * reads this package; this package never reads {@code feature}.
 *
 * <p><b>Start with:</b> {@code PlanParser.parse}, {@code Plan}.
 *
 * <p>Depends on: json.
 */
package dev.fmangoni.forge.plan;
