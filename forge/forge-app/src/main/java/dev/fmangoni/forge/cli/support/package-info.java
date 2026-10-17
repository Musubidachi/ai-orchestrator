/**
 * What every command needs before it can do anything: the flags, the ledgers, the agents, the
 * child-process guard, and every run's state.
 *
 * <p><b>Belongs here:</b> Argument parsing, the wiring each command builds the same way, and
 * reading the runs directory.
 *
 * <p><b>Not here:</b> Any command's own behaviour, and the dispatch. Below the commands rather than
 * beside {@code Forge} so that neither the entry point nor the commands point up at the other.
 *
 * <p><b>Start with:</b> {@code Args.option}, {@code Wiring.agents}, {@code ChildGuard.install},
 * {@code RunStates.all}.
 *
 * <p>Depends on: agent, agent.ledger, agent.policy, process, project, state.
 */
package dev.fmangoni.forge.cli.support;
