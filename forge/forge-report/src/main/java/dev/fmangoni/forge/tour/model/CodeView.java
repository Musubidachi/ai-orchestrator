package dev.fmangoni.forge.tour.model;

import java.util.List;

/**
 * The code on screen for one scene, and the lines lit up while it is spoken.
 *
 * <p>A window rather than a whole file: the listener is being told about one method, and a screen
 * of the thirty lines around it is the difference between following along and scrolling. The line
 * numbers are the file's own, so what is on screen can be found in the editor afterwards.
 */
public record CodeView(String file, int firstLine, List<String> lines, int litFrom, int litTo) {

    /** For a scene with nothing to show: a spoken introduction, or the closing summary. */
    public static CodeView none() {
        return new CodeView("", 0, List.of(), 0, 0);
    }

    public boolean empty() {
        return lines.isEmpty();
    }

    /** True when the file's own line number falls inside the lit range. */
    public boolean lit(int lineNumber) {
        return lineNumber >= litFrom && lineNumber <= litTo;
    }
}
