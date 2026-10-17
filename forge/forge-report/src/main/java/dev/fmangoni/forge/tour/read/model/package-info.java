/**
 * What the parser found in a source file.
 *
 * <p><b>Belongs here:</b> {@code ParsedType} and {@code ParsedMethod} — a name, a kind, a javadoc
 * note and a line range, plus the file's own lines to show.
 *
 * <p><b>Not here:</b> Anything that decides what to say about them. These are what the reading
 * produced; the wording lives in {@code tour.say}, which reads this and is not read by it.
 *
 * <p><b>Start with:</b> {@code ParsedType.between}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.tour.read.model;
