/**
 * Finding the code a tour is about, and reading it.
 *
 * <p><b>Belongs here:</b> {@code MavenParent} (the child projects a parent lists, in its own
 * order), {@code SourceFiles} (the main sources of one module) and {@code JavaSource}, which parses
 * a file with the compiler's own parser and hands back the type, its methods and their line ranges.
 *
 * <p><b>Not here:</b> Any opinion about what the code means. This layer reports what is written;
 * turning that into sentences is {@code tour.say}. Nothing here calls a model — a tour is assembled
 * from the source and its notes, which is what lets it cost nothing and be run on every change.
 *
 * <p><b>Start with:</b> {@code MavenParent.modulesOf}, {@code JavaSource.parse}.
 *
 * <p>Depends on: tour.read.model.
 */
package dev.fmangoni.forge.tour.read;
