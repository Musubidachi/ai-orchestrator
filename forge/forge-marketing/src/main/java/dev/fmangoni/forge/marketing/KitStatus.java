package dev.fmangoni.forge.marketing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * How the last kit came out.
 *
 * @param outcome {@code ready}, {@code problems}, or {@code unavailable} when no model could be asked
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KitStatus(Instant madeAt, String commit, String agent, String outcome, String detail,
                     List<KitProblem> problems) {

    public static KitStatus load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.mapper().readValue(JsonFile.read(file), KitStatus.class);
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return null;
        }
    }
}
