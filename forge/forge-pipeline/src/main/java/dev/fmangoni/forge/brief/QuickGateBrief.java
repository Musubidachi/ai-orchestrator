package dev.fmangoni.forge.brief;

import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * What a session is told when the gate before it was a quick one, put in front of its
 * {@link ContinueBrief}.
 *
 * <p>The continue brief speaks of "the acceptance commands", and after a quick gate those are only
 * the ones that ran. Read without this, a quick gate that passed says that every command passes,
 * and the builder has no reason to run the slower ones before it reports the work complete: the
 * full gate then fails on them, and the next turn is a repair.
 */
@UtilityClass
public class QuickGateBrief {

    /** The note, or nothing when the last gate ran every command. */
    public String of(List<String> notRun) {
        if (notRun == null || notRun.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("""
                The gate after your last turn was a quick one. You reported the work unfinished, so \
                it ran only the acceptance commands that usually finish quickly, and left these for \
                the full gate, which runs once you report the work complete:
                """);
        for (String command : notRun) {
            out.append("  - `").append(command).append("`\n");
        }
        out.append("""
                Below, "the acceptance commands" means the ones that ran. The ones above have not \
                been run against your latest changes, so run them yourself before you report the \
                work complete.

                """);
        return out.toString();
    }
}
