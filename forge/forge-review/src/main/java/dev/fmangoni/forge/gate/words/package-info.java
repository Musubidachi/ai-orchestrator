/**
 * The gate's comparison of what each screen says in every look, so that a theme cannot change a
 * label or a figure without the gate noticing.
 *
 * <p><b>Belongs here:</b> reading the words a project's suite saved per screen and look, and
 * failing a passed gate when a look says something different from the default.
 *
 * <p><b>Not here:</b> running the commands or collecting evidence, which is {@code gate}.
 *
 * <p><b>Start with:</b> {@code SameWords.around}.
 *
 * <p>Depends on: gate.
 */
package dev.fmangoni.forge.gate.words;
