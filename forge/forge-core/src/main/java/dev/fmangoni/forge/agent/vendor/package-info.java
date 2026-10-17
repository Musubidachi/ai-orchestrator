/**
 * The CLIs Forge actually drives, one class per vendor, plus the command line each one takes.
 *
 * <p><b>Belongs here:</b> An {@code Agent} implementation per vendor, its {@code *Args} command
 * line, finding the executables on disk, and the environment every agent is started without
 * ({@code SubscriptionOnly}).
 *
 * <p><b>Not here:</b> Deciding which vendor to use, or what to ask it. The command lines are kept
 * apart from the code that runs them so a test can pin every flag without spending quota.
 *
 * <p><b>Start with:</b> {@code ClaudeAgent}, {@code CodexAgent}, {@code AntigravityAgent}, {@code
 * CliLocator}.
 *
 * <p>Depends on: agent.model, agent.stream, agent.vendor.model, process.
 */
package dev.fmangoni.forge.agent.vendor;
