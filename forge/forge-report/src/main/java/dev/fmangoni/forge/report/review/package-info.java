/**
 * The part of the page you act on: what the other model found, and the one form that carries every
 * ruling.
 *
 * <p><b>Belongs here:</b> The cross-review section, the decision section and the form itself,
 * including the feature and idea rulings and the notes already on record.
 *
 * <p><b>Not here:</b> Handling a submission, which is {@code jobs.submit}. The form is rendered
 * even when the review has not run: what a missing review blocks is accepting, not writing
 * something down.
 *
 * <p><b>Start with:</b> {@code ReviewSection.render}, {@code DecisionSection.render}, {@code
 * DecisionForm}.
 *
 * <p>Depends on: feature, project, report.shell, review.decision, review.decision.model, review.feature, review.model, security.model, state.
 */
package dev.fmangoni.forge.report.review;
