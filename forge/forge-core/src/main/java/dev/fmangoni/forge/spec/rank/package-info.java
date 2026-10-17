/**
 * The wishlist as an order: projects first, then improvements, contiguous and unique.
 *
 * <p><b>Belongs here:</b> Computing a ranking and writing it back into the spec files, touching
 * only the priority line.
 *
 * <p><b>Not here:</b> Deciding what to run next — the queue reads the rank but weighs other things
 * too.
 *
 * <p><b>Start with:</b> {@code Ranking.normalised}, {@code RankWriter.apply}.
 *
 * <p>Depends on: spec, spec.model.
 */
package dev.fmangoni.forge.spec.rank;
