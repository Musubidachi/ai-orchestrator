/**
 * A narrated tour of a Maven project, assembled from its own source.
 *
 * <p><b>Belongs here:</b> {@code CodeTour}, which walks a parent's child projects and turns every
 * class into a chapter, and {@code TourSummary}, the closing chapter — the walkthrough itself when
 * the toured code is a project Forge built, and what the tour covered when it is not.
 *
 * <p><b>Not here:</b> Reading the source ({@code tour.read}), the wording ({@code tour.say}), or
 * playing it back ({@code tour.show}). This package decides what the tour is and in what order,
 * which is all it decides.
 *
 * <p><b>Start with:</b> {@code CodeTour.of}.
 *
 * <p>Depends on: project, review, state, tour.model, tour.read, tour.read.model, tour.say,
 * walkthrough.
 */
package dev.fmangoni.forge.tour;
