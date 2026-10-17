/**
 * Running one external program: the command, the result, and making sure no child outlives Forge.
 *
 * <p><b>Belongs here:</b> {@code CliRunner}, the OS-level command line, the result of one
 * invocation, the time cap that does not count a sleeping machine ({@code Deadline}), the registry
 * that kills orphans at startup and shutdown, and {@code ProcessTree}: the only way anything in
 * Forge may ask what a command started. Never {@code ProcessHandle.descendants()}, which on an
 * exited command returns whoever now has its recycled process id for a parent.
 *
 * <p><b>Not here:</b> What a program printed ({@code process.output}) and running the built project
 * ({@code process.local}). Knowledge of any particular program belongs to the package that drives
 * it.
 *
 * <p><b>Start with:</b> {@code CliRunner.run}, {@code ProcessRegistry}.
 *
 * <p>Depends on: process.output.
 */
package dev.fmangoni.forge.process;
