/**
 * Asking an agent for a spec, or for a better version of one section of it.
 *
 * <p><b>Belongs here:</b> The suggester and what comes back: a proposal, one vendor's reply, and a
 * rewritten section.
 *
 * <p><b>Not here:</b> Writing anything to disk. A suggestion only fills the form; saving is a
 * separate, deliberate act.
 *
 * <p><b>Start with:</b> {@code Suggester.suggest}, {@code Suggester.refine}.
 *
 * <p>Depends on: agent, agent.model, brief.draft, json, process, spec.model, spec.tier, ui.editor.
 */
package dev.fmangoni.forge.ui.suggest;
