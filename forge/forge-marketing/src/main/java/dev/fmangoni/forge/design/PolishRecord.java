package dev.fmangoni.forge.design;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The design a project was last asked to look like, kept for the report.
 *
 * <p>Apart from the board, because the board belongs to one request for designs and is emptied by
 * the next. A polish that was built is a fact about the project; asking to see some new designs
 * afterwards used to erase it, and the report then showed a before and an after with nothing to
 * say what they were aiming at.
 *
 * @param option   the design, as it was offered
 * @param chosenAt when it was picked
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolishRecord(DesignOption option, Instant chosenAt) {

    /** The record, or null when no design has ever been chosen for this project. */
    public static PolishRecord load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.mapper().readValue(JsonFile.read(file), PolishRecord.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return null;
        }
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, Json.mapper().writeValueAsString(this));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
