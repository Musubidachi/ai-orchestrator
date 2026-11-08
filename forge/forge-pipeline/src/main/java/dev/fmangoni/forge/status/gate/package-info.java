/**
 * The last gate, as a project's panel shows it: quick or full, what it did with each command, the
 * session it followed and the gates before it.
 *
 * <p><b>Belongs here:</b> {@code LastGateReader}, which reads a project's state and its spec's
 * commands; {@code GateSummary}, which works out from what the state recorded what the last gate did
 * with each one; {@code GateHistory}, the list of a version's gates in {@code gates.json}, with
 * {@code PastGate}, one line of it; and {@code LastGate} with {@code GateCommand}, what the page
 * receives.
 *
 * <p><b>Not here:</b> Anything that runs a gate or decides which commands a quick one runs. That is
 * {@code pipeline.step.QuickGate}, whose threshold this only reports. Like the rest of the status it
 * is for a person to look at, and never reaches a brief: the outline notes each gate in the history
 * once it has run, and nothing reads the history back but the panel.
 *
 * <p><b>Start with:</b> {@code LastGateReader.read}, then {@code GateSummary.of}.
 *
 * <p>Depends on: json, pipeline.step, project, spec, state.
 */
package dev.fmangoni.forge.status.gate;
