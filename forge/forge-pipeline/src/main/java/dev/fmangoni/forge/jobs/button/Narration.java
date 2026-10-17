package dev.fmangoni.forge.jobs.button;

/**
 * A walkthrough as the dashboard needs it: what to say, and where the recording of it will be.
 *
 * @param text  the walkthrough itself, or the reason there is none — either way it is what the page
 *              reads out, because "no spec with id 004" is worth hearing too
 * @param audio where the {@code .wav} is being written, relative to the root, or blank when nothing
 *              is being written
 */
public record Narration(String text, String audio) {

    static Narration problem(String reason) {
        return new Narration(reason, "");
    }
}
