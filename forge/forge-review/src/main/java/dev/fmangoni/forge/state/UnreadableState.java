package dev.fmangoni.forge.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * A state file exists and cannot be read.
 *
 * <p>Its own type so the two kinds of caller can treat it differently. Code that acts on a
 * project must fail on it: reading it as "no state" would create a fresh run over the top and
 * throw the project's history away. Code that only surveys — the dashboard's list, the queue's
 * sort — must skip it, because it used to throw straight through them, so one hand-edited file
 * blanked every card on the dashboard and stopped every other project from starting.
 */
public final class UnreadableState extends UncheckedIOException {
    UnreadableState(Path file, IOException cause) {
        super("cannot read run state " + file + ": " + cause.getMessage(), cause);
    }
}
