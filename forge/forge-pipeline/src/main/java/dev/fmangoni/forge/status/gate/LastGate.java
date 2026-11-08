package dev.fmangoni.forge.status.gate;

import dev.fmangoni.forge.pipeline.step.QuickGate;
import java.util.List;

/**
 * A project's last gate, as its panel shows it.
 *
 * @param ran         whether a gate has run on this version at all; the panel shows nothing until one has
 * @param quick       a quick gate: it ran only the commands that usually take under {@code slowSeconds}
 *                    and left the rest for the full gate
 * @param passed      whether everything it ran passed, the gate's own checks included
 * @param check       the check it stopped at once every command had passed (the run file, the
 *                    recordings, the default look...), or empty
 * @param seconds     what the commands it ran took, this time
 * @param slowSeconds a command whose usual time is this or more waits for the full gate
 * @param commands    every acceptance command, in the order the gate runs them
 * @param after       the session it followed; 0 when that is not known
 * @param history     the gates on this version, oldest first, the last of them this one; empty for a
 *                    version whose gates ran before they were noted ({@link GateHistory})
 */
public record LastGate(boolean ran, boolean quick, boolean passed, String check, long seconds,
                       int slowSeconds, List<GateCommand> commands, int after, List<PastGate> history) {

    /** No gate yet, or no project by that id. */
    static LastGate none() {
        return new LastGate(false, false, false, "", 0, QuickGate.SLOW_SECONDS, List.of(), 0, List.of());
    }

    /** The same gate, with the session it followed and the gates before it. */
    LastGate with(int after, List<PastGate> history) {
        return new LastGate(ran, quick, passed, check, seconds, slowSeconds, commands, after, List.copyOf(history));
    }
}
