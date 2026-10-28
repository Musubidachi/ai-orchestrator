/**
 * The cheap pass: one small call per feature the agent claims, on the cheapest model the reviewer
 * has.
 *
 * <p><b>Belongs here:</b> The pass, its prompt, and the verdict for each claim; and the one question
 * put to the same model about a visual polish, whether it changed anything besides appearance
 * ({@code ScopeCheck}).
 *
 * <p><b>Not here:</b> The expensive review that follows it. A refusal stops one reviewer's pass
 * rather than being asked again per claim; what it left unanswered goes to the next reviewer
 * ({@code FeaturePass}). An unchecked claim never counts as one that held.
 *
 * <p><b>Start with:</b> {@code FeaturePass.run}, {@code FeatureReview.run}, {@code FeatureCheck}.
 *
 * <p>Depends on: agent.model, json, plan, spec.model.
 */
package dev.fmangoni.forge.review.feature;
