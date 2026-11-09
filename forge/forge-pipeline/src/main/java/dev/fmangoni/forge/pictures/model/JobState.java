package dev.fmangoni.forge.pictures.model;

/**
 * Where one job stands. Worked out from the picture on disk and the record each time it is asked, never
 * stored, so a picture replaced by hand can never leave a state behind that is no longer true.
 */
public enum JobState {

    /** Nothing drawn yet, and nothing in the way. */
    TO_DRAW,

    /** Nothing drawn yet, and it waits: for the job its card names to be kept, or behind a STOP line. */
    WAITING,

    /** A person asked for it again: the next attempt is its prompt with their note added. */
    REDRAW,

    /** Drawn, and this picture has not been checked yet. */
    DRAWN,

    /** Checked, and waiting for a person to look at it. */
    CHECKED,

    /** A person kept it, and it has not been placed. */
    KEPT,

    /** Placed in the project it was drawn for. */
    PLACED
}
