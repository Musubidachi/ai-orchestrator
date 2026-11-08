package dev.fmangoni.forge.jobs.pause;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Why a person paused a project, and since when: {@code pause.json} in its run folder.
 *
 * <p>Beside the state rather than in it. The state holds the pause itself, as a cool-off (see
 * {@link Pausing}); this only says why, so the record every part of Forge reads gains nothing.
 *
 * @param reason what the person wrote; empty when they wrote nothing
 * @param at     when it was first paused; changing the reason keeps it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PauseNote(String reason, Instant at) {

    private static final String FILE = "pause.json";

    private static final ObjectMapper JSON = Json.mapper();

    /**
     * The note in a run folder, or empty.
     *
     * <p>Quietly empty when it cannot be read: the page asks for every note on each refresh, and a
     * note lost costs only the reason, never the pause.
     */
    public static Optional<PauseNote> load(Path runRoot) {
        Path file = runRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(JsonFile.read(file), PauseNote.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void save(Path runRoot) throws IOException {
        JsonFile.write(runRoot.resolve(FILE), JSON.writeValueAsString(this));
    }

    /** Removes the note once the pause is lifted. A note left behind is harmless: only a held project's is read. */
    public static void forget(Path runRoot) {
        try {
            Files.deleteIfExists(runRoot.resolve(FILE));
        } catch (IOException e) {
            System.err.println("could not remove " + runRoot.resolve(FILE) + ": " + e.getMessage());
        }
    }
}
