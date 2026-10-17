/**
 * The cheap pass: one small call per feature the agent claims, on the cheapest model the reviewer
 * has.
 *
 * <p><b>Belongs here:</b> The pass, its prompt, and the verdict for each claim; and the one question
 * put to the same model about a visual polish, whether it changed anything besides appearance
 * ({@code ScopeCheck}).
 *
 * <p><b>Not here:</b> The expensive review that follows it. A refusal stops the pass rather than
 * being asked again per claim, and an unchecked claim never counts as one that held.
 *
 * <p><b>Start with:</b> {@code FeatureReview.run}, {@code FeatureCheck}.
 *
 * <p>Depends on: agent.model, json, plan.
 */
package dev.fmangoni.forge.review.feature;
