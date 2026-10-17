/**
 * A project's review dealt as cards, for the quick review page.
 *
 * <p><b>Belongs here:</b> {@code Deck}, which reads the run and sorts its items into what must be
 * answered one at a time and what can be skimmed; {@code Cards} and {@code FeatureCards}, which
 * word one each; and the three shapes the page receives.
 *
 * <p><b>Not here:</b> What a ruling means or does ({@code jobs.submit}), and the form in the
 * report ({@code report.review}), which this shares its ids, its blocked notice and its came-back
 * line with so the two pages cannot disagree.
 *
 * <p><b>Start with:</b> {@code Deck.of}, {@code DeckView}.
 *
 * <p>Depends on: feature, project, report.review, review.decision, review.decision.model, review.model, security.model, state.
 */
package dev.fmangoni.forge.report.deck;
