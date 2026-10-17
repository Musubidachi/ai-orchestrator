/**
 * The model ladder, and which rung each job on a project takes.
 *
 * <p><b>Belongs here:</b> {@code ModelTiers} (the ordering, from {@code models.json}) and {@code
 * ModelChoice} (planning at the top, building in the middle climbing with repair rounds, feature
 * checks at the bottom, the review at the top).
 *
 * <p><b>Not here:</b> Calling a model. Nothing here knows what a turn is.
 *
 * <p><b>Start with:</b> {@code ModelChoice}, {@code ModelTiers}.
 *
 * <p>Depends on: agent.catalog, agent.model, json, spec.model.
 */
package dev.fmangoni.forge.spec.tier;
