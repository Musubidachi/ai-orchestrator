/**
 * The cross-review: the model that did not build the project reads the change.
 *
 * <p><b>Belongs here:</b> Running the review, its prompt, parsing whatever the model actually
 * replied with, and persisting it.
 *
 * <p><b>Not here:</b> The findings themselves ({@code review.model}), the cheap per-feature pass
 * before it ({@code review.feature}), and the rulings a person makes afterwards ({@code
 * review.decision}).
 *
 * <p><b>Start with:</b> {@code ReviewStage.review}.
 *
 * <p>Depends on: agent.model, json, review.feature, review.model, spec.model.
 */
package dev.fmangoni.forge.review;
