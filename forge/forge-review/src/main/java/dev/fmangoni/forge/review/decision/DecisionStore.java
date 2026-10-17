package dev.fmangoni.forge.review.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Keeps what the reader has already ruled on, so the report is not a form to fill in twice.
 *
 * <p>A project is read across several sittings, and the items it lists are regenerated every time
 * a session ends. Without this, ruling on nine disclosures and then coming back after a repair
 * would mean starting again — which in practice means nobody does it a second time.
 */
@UtilityClass
public class DecisionStore {

    /**
     * Time types are registered because a comment carries when it was made.
     *
     * <p>Without the module Jackson throws part-way through writing, which here would mean a
     * half-written decisions file — and the same omission in {@code Http} once served a truncated
     * 200 that looked like success. The sixteenth mapper in this codebase to configure itself
     * separately; see IMPROVEMENTS.md #4.
     */
    private final ObjectMapper JSON = Json.mapper();

    public List<Decision> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(
                    JsonFile.read(file), Decision[].class));
        } catch (Exception e) {
            // A hand-edited or half-written file must not stop the report rendering; the worst
            // case is being asked to rule on something again.
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    public void save(Path file, List<Decision> decisions) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(decisions));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
