/**
 * The command line: the entry point, the flags, and the wiring every command shares.
 *
 * <p><b>Belongs here:</b> {@code Forge}, which reads the subcommand, installs the child-process
 * guard and refreshes the model ladder for the two commands that can start an agent, and hands
 * over.
 *
 * <p><b>Not here:</b> The commands themselves ({@code cli.command}) and what they share ({@code
 * cli.support}). Behaviour belongs to the packages a command calls.
 *
 * <p><b>Start with:</b> {@code Forge.main}.
 *
 * <p>Depends on: cli.command, cli.support, process.output, project, spec.tier.
 */
package dev.fmangoni.forge.cli;
