/**
 * What a person ruled on the outstanding items, kept across submissions.
 *
 * <p><b>Belongs here:</b> Folding a submission into what was already on record, and persisting it;
 * and the history of every submitted review ({@code ReviewHistory}), which is only ever added to.
 *
 * <p><b>Not here:</b> What follows from a ruling, which is {@code jobs.submit}.
 *
 * <p><b>Start with:</b> {@code Decisions.merge}, {@code DecisionStore}.
 *
 * <p>Depends on: json, review.decision.model.
 */
package dev.fmangoni.forge.review.decision;
