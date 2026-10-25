/**
 * The judge of done: Forge runs the spec's acceptance commands itself, and collects the evidence
 * they name.
 *
 * <p><b>Belongs here:</b> {@code Gate}, its results, evidence collection, and {@code RunFile}: the
 * {@code run.bat} every project owes the person who will try it.
 *
 * <p><b>Not here:</b> Repairing a failure (the pipeline runs a repair turn with {@code
 * brief.RepairBrief}). The agent's own claim of success never counts here.
 *
 * <p><b>Start with:</b> {@code Gate.run}, {@code GateResult}, {@code CommandResult}.
 *
 * <p>Depends on: feature, git, process, process.local.
 */
package dev.fmangoni.forge.gate;
