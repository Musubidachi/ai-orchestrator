package dev.fmangoni.forge.design;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The looks on offer for one project, and which was chosen.
 *
 * @param askedAt  when the vendors were asked
 * @param note     what the person said they wanted, or blank
 * @param state    {@code asking} while vendors are still being asked, {@code ready} once there is
 *                 something to choose from, {@code none} when nobody offered anything usable, and
 *                 {@code interrupted} when the asking stopped before every vendor had answered
 * @param options  every look offered, in the order the vendors were asked
 * @param refused  why a vendor contributed nothing, one line each
 * @param chosen     the id of the option being built, or blank while none has been picked
 * @param chosenAt   when it was picked
 * @param chosenNote what the person added when they picked it, or blank. Kept with a pick saved for
 *                   later, which used to drop it: the page puts it back when the pick is built.
 * @param extra      the kind of extra these designs were drawn as ({@link #THEME}, {@link #VARIANTS}
 *                   or {@link #ANIMATION}), to be added beside the current look; blank when they are
 *                   looks to replace it with
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesignBoard(Instant askedAt, String note, String state, List<DesignOption> options,
                          List<String> refused, String chosen, Instant chosenAt, String chosenNote,
                          String extra) {

    public static final String ASKING = "asking";
    public static final String READY = "ready";
    public static final String NONE = "none";
    public static final String INTERRUPTED = "interrupted";

    /** The id of the design a request for more like it started from, kept first on the new board. */
    public static final String LIKED = "LIKED";

    /** Extras: a whole alternative look, alternative versions of one thing, and new motion. */
    public static final String THEME = "theme";
    public static final String VARIANTS = "variants";
    public static final String ANIMATION = "animation";

    /** Whether this names a kind of extra. */
    public static boolean isExtra(String kind) {
        return THEME.equals(kind) || VARIANTS.equals(kind) || ANIMATION.equals(kind);
    }

    /** The option that was picked, if one was and it is still on the board. */
    public Optional<DesignOption> chosenOption() {
        return option(chosen);
    }

    public Optional<DesignOption> option(String id) {
        return options == null || id == null || id.isBlank()
                ? Optional.empty()
                : options.stream().filter(option -> id.equals(option.id())).findFirst();
    }

    /**
     * The same board, said to have been cut short.
     *
     * <p>For a board left saying {@code asking} by a request that is no longer running: the editor
     * was stopped or the job died. Left as it was, the page went on showing a request in progress
     * and would not let another be made.
     */
    public DesignBoard interrupted() {
        return new DesignBoard(askedAt, note, INTERRUPTED, options, refused, chosen, chosenAt, chosenNote, extra);
    }

    /**
     * The same board with one option marked as the one to build.
     *
     * @param note what the person added when picking it, or blank
     */
    public DesignBoard choosing(String id, String note) {
        return new DesignBoard(askedAt, this.note, state, options, refused, id, Instant.now(),
                note == null ? "" : note.strip(), extra);
    }

    /** The board on disk, or null when the vendors have never been asked. */
    public static DesignBoard load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.mapper().readValue(JsonFile.read(file), DesignBoard.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return null;
        }
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, Json.mapper().writeValueAsString(this));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
