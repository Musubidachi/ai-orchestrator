/**
 * Everything a tour plays that is not speech, generated from arithmetic.
 *
 * <p><b>Belongs here:</b> {@code Music} (a lo-fi bed at 75 beats a minute), {@code Sfx} (the tick
 * as a line lands and the chime as a chapter opens) and {@code Score}, which lays all three out
 * against the scene timings and writes one {@code .wav}.
 *
 * <p><b>Not here:</b> Speech, which is {@code walkthrough.Speech} and the narration files in
 * {@code tour.show}; and anything visual. Nothing here reads a source file or knows what a tour is
 * about — it is handed durations and gives back sound.
 *
 * <p>Nothing is downloaded and nothing is licensed: Forge owns nothing it did not make, and a few
 * hundred lines of arithmetic need no network, no file to keep and no permission. The generator is
 * seeded, so the same tour always gets the same music — output that differs between two runs of the
 * same input cannot be diffed.
 *
 * <p><b>Start with:</b> {@code Score.write}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.tour.sound;
