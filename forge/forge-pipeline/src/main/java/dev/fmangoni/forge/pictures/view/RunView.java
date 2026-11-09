package dev.fmangoni.forge.pictures.view;

import dev.fmangoni.forge.pictures.model.Verdict;
import java.time.Instant;
import java.util.List;

/**
 * A check or a placing, as a page shows it.
 *
 * @param verdict what it found
 * @param exit    its exit code, or -1 when it ran out of time
 * @param lines   what it printed
 * @param log     a link to its whole log
 * @param at      when it finished
 */
public record RunView(Verdict verdict, int exit, List<String> lines, String log, Instant at) {

    public RunView {
        lines = List.copyOf(lines);
    }
}
