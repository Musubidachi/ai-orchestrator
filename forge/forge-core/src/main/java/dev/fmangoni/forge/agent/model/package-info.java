/**
 * What a turn is and how it ended: the vendor it goes to, the task it carries, and the result it
 * comes back as.
 *
 * <p><b>Belongs here:</b> The records and enums the whole codebase passes around when it talks
 * about an agent turn.
 *
 * <p><b>Not here:</b> Anything that runs a turn or reads a CLI's output. A record here must not
 * call back into the code that fills it in — {@code AgentResult} used to classify itself through
 * {@code Signals}, which is exactly the dependency this package exists to keep out.
 *
 * <p><b>Start with:</b> {@code AgentTask}, {@code AgentResult}, {@code AgentKind}.
 *
 * <p>Depends on: process.
 */
package dev.fmangoni.forge.agent.model;
