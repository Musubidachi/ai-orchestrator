/**
 * What Forge remembers about one project between turns and restarts: its {@code state.json} and
 * command timings.
 *
 * <p><b>Belongs here:</b> {@code RunState} (which records gate results, review findings and the
 * last plan), reading and writing it, and how long each acceptance command usually takes.
 *
 * <p><b>Not here:</b> Paths and phases, which are {@code project} so that capabilities can use them
 * without depending on this package.
 *
 * <p><b>Start with:</b> {@code RunStore}, {@code RunState}, {@code Timings}.
 *
 * <p>Depends on: agent.model, gate, json, plan, project, review.model.
 */
package dev.fmangoni.forge.state;
