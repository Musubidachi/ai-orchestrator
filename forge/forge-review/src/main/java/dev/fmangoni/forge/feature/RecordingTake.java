package dev.fmangoni.forge.feature;

import java.nio.file.Path;

/**
 * One feature's recording, and what is wrong with it.
 *
 * @param file the file found, or {@code null} when the project produced none
 * @param clip what the bytes turned out to be; meaningless when {@code file} is null
 */
public record RecordingTake(Feature feature, Path file, Clip clip) {

    public boolean missing() {
        return file == null;
    }

    public boolean usable() {
        return file != null && clip.usable();
    }

    /** Why this feature is still owed, in a sentence that names the feature. */
    public String problem() {
        if (missing()) {
            return "no recording was produced";
        }
        return "the file at " + file.getFileName() + " " + clip.rejected();
    }
}
