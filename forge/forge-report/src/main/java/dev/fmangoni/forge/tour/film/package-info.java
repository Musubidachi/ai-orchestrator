/**
 * A tour as a video file: drawn, timed to the narration, and encoded.
 *
 * <p><b>Belongs here:</b> {@code SceneImage} (one scene drawn at one instant), {@code Shot} (where
 * a scene has got to), {@code Motion} (when things happen inside a scene), {@code Frames} (drawn
 * frames piped straight to the encoder) and {@code TourFilm}, which puts the three tracks together.
 *
 * <p><b>Not here:</b> Anything that decides what the tour says, and the sounds themselves, which
 * are {@code tour.sound}. Nothing in here changes a word of the tour — a film is a way of playing
 * one, not a different one.
 *
 * <p>Frames go down a pipe rather than onto the disk. A tour of a large project is tens of
 * thousands of them, and written out as pictures that is gigabytes of files, most of the running
 * time spent compressing images nobody looks at, and a directory to clear up afterwards.
 *
 * <p><b>Start with:</b> {@code TourFilm.write}.
 *
 * <p>Depends on: tour.model, tour.show, tour.sound.
 */
package dev.fmangoni.forge.tour.film;
