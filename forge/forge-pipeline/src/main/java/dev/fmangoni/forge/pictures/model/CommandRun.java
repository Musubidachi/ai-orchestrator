package dev.fmangoni.forge.pictures.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * One run of a job's check or placing, as the record keeps it.
 *
 * @param draft   the stamp of the picture it ran on
 * @param at      when it finished
 * @param exit    its exit code, or -1 when it ran out of time
 * @param verdict what that means
 * @param lines   what it printed, without the blank lines
 * @param log     its whole log, relative to the batch folder
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandRun(String draft, Instant at, int exit, Verdict verdict, List<String> lines, String log) {

    public CommandRun {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
