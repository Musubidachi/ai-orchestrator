/**
 * What a narrated tour of a codebase is, as data.
 *
 * <p><b>Belongs here:</b> {@code Tour}, {@code Chapter}, {@code Scene} and {@code CodeView} — a
 * heading, a sentence to say, the lines on screen and which of them are lit.
 *
 * <p><b>Not here:</b> Reading the source ({@code tour.read}), deciding what to say about it
 * ({@code tour.say}), or playing it back ({@code tour.show}). A tour is built once and played three
 * ways, so the shape of it sits below all three and depends on none of them.
 *
 * <p><b>Start with:</b> {@code Tour}, {@code Scene.seconds}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.tour.model;
