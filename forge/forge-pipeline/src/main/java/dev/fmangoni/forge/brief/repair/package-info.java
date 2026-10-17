/**
 * The prompts for a turn that exists because something was wrong: a failed gate, a blocking review
 * finding, a serious security finding, or a person asking for changes.
 *
 * <p><b>Belongs here:</b> One prompt per kind of repair.
 *
 * <p><b>Not here:</b> Deciding that a repair is owed, which is the pipeline's.
 *
 * <p><b>Start with:</b> {@code RepairBrief.of}, {@code ReviewRepairBrief.of}, {@code
 * SecurityRepairBrief.of}, {@code FeedbackBrief.itemised}.
 *
 * <p>Depends on: brief, feature, gate, plan, review.decision.model, review.model, security.model.
 */
package dev.fmangoni.forge.brief.repair;
