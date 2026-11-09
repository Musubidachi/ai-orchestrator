package dev.fmangoni.forge.pictures.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Data;

/**
 * Everything Forge kept about one job: each check, each ruling, and the placing.
 *
 * <p>The lists grow with each new picture. What applies is what was said about the picture on disk
 * now, found by its stamp, so a check or a ruling about an earlier picture never stands for a later one.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRecord {

    private List<CommandRun> checks = new ArrayList<>();

    private List<Ruling> rulings = new ArrayList<>();

    /** The stamp of a kept picture a person asked to have placed, until the placing has run. */
    private String placeAsked;

    /** The placing that worked, or null. */
    private CommandRun placed;

    /** The last placing that failed, until one works. */
    private CommandRun placeFailed;

    /** The latest check of this picture. */
    public Optional<CommandRun> checkOf(String stamp) {
        for (int i = checks.size() - 1; i >= 0; i--) {
            if (checks.get(i).draft().equals(stamp)) {
                return Optional.of(checks.get(i));
            }
        }
        return Optional.empty();
    }

    /** The latest ruling on this picture. */
    public Optional<Ruling> rulingOf(String stamp) {
        for (int i = rulings.size() - 1; i >= 0; i--) {
            if (rulings.get(i).draft().equals(stamp)) {
                return Optional.of(rulings.get(i));
            }
        }
        return Optional.empty();
    }

    /** The latest ruling of all, whichever picture it was about. */
    public Optional<Ruling> lastRuling() {
        return rulings.isEmpty() ? Optional.empty() : Optional.of(rulings.get(rulings.size() - 1));
    }

    /** How many different pictures were checked: the times it was drawn, as far as Forge saw. */
    public int drawings() {
        return (int) checks.stream().map(CommandRun::draft).distinct().count();
    }
}
