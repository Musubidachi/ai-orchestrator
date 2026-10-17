package dev.fmangoni.forge.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The dashboard's own preferences, kept across restarts.
 *
 * <p>Exists because {@code autoRun} was held in memory only. Every restart of the editor silently
 * turned it off, so the queue stopped moving and stayed stopped until someone noticed the unticked
 * box — which, on a machine where the editor is restarted for every rebuild, is most of the time.
 * A preference a person set once should not need setting again because a process exited.
 *
 * <p>Still off on a first run. Starting coding agents against someone's subscription because they
 * launched an editor is not a reasonable default for a fresh clone; it is a reasonable default for
 * a machine where it has been switched on deliberately, and that is exactly the difference this
 * file records.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashboardSettings {

    private static final ObjectMapper JSON = Json.mapper();

    /** Whether the queue keeps itself moving, and with it whether a restart resumes work. */
    private boolean autoRun;

    public static DashboardSettings load(Path forgeRoot) {
        Path file = file(forgeRoot);
        if (!Files.isRegularFile(file)) {
            return new DashboardSettings();
        }
        try {
            return JSON.readValue(JsonFile.read(file),
                    DashboardSettings.class);
        } catch (Exception e) {
            // Off is the safe reading of an unreadable file: it spends nothing.
            System.err.println("Ignoring unreadable dashboard.json: " + e.getMessage());
            return new DashboardSettings();
        }
    }

    public void save(Path forgeRoot) {
        try {
            Path file = file(forgeRoot);
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(this));
        } catch (IOException e) {
            // A preference that failed to persist is a small loss; failing the launch is not.
            System.err.println("could not save dashboard.json: " + e.getMessage());
        }
    }

    private static Path file(Path forgeRoot) {
        return forgeRoot.resolve("dashboard.json");
    }
}
