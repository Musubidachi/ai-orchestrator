/**
 * The last gate, as a project's panel shows it: quick or full, and what it did with each command.
 *
 * <p><b>Belongs here:</b> {@code LastGateReader}, which reads a project's state and its spec's
 * commands; {@code GateSummary}, which works out from what the state recorded what the last gate did
 * with each one; and {@code LastGate} with {@code GateCommand}, what the page receives.
 *
 * <p><b>Not here:</b> Anything that runs a gate or decides which commands a quick one runs. That is
 * {@code pipeline.step.QuickGate}, whose threshold this only reports. Like the rest of the status it
 * is for a person to look at, and never reaches a brief.
 *
 * <p><b>Start with:</b> {@code LastGateReader.read}, then {@code GateSummary.of}.
 *
 * <p>Depends on: pipeline.step, project, spec, state.
 */
package dev.fmangoni.forge.status.gate;
