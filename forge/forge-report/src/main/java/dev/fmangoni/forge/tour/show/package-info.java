/**
 * Playing a tour back without a video: a page, and the narration as sound files.
 *
 * <p><b>Belongs here:</b> {@code TourPage} (one HTML file that plays itself in the browser's own
 * voice) and {@code TourAudio} (a {@code .wav} per scene, and how long each one runs).
 *
 * <p><b>Not here:</b> Any decision about what the tour says, and the film, which is
 * {@code tour.film}. These two are the versions that need nothing installed.
 *
 * <p><b>Start with:</b> {@code TourPage.write}, {@code TourAudio.write}.
 *
 * <p>Depends on: json, report.shell, tour.model, walkthrough.
 */
package dev.fmangoni.forge.tour.show;
