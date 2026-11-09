package dev.fmangoni.forge.pictures.model;

/** What a check or a placing said, read from its exit code the way Forge reads a gate command's. */
public enum Verdict {

    /** Exit 0: nothing needs a look. */
    OK,

    /** Exit 42: it passed, and a person should look at what it printed. */
    LOOK,

    /** Exit 1: it found a problem. */
    PROBLEM,

    /** Any other exit, or it ran out of time: the command itself went wrong. */
    ERROR
}
