package dev.fmangoni.forge.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.experimental.UtilityClass;

/**
 * Reads and writes {@code state.json}.
 *
 * <p>Written atomically — to a temporary file, then moved into place. The state file is what an
 * unattended run resumes from after a quota window, a reboot, or a kill, so a half-written one
 * would lose the very project it was recording. A move is cheap; a corrupted state file costs the
 * whole project's quota to rebuild.
 */
@UtilityClass
public class RunStore {

    private final ObjectMapper JSON = Json.mapper();

    public Optional<RunState> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            // Through JsonFile, like every other file here a person might open in Notepad — which
            // writes a byte-order mark that Jackson refuses outright.
            return Optional.of(JSON.readValue(JsonFile.read(file), RunState.class));
        } catch (IOException e) {
            throw new UnreadableState(file, e);
        }
    }

    /**
     * For callers that only look: an unreadable file is reported and left out, not thrown.
     *
     * <p>Reported once until it changes. The dashboard surveys every project every few seconds, and
     * one damaged file filled the log with the same two lines hundreds of times a minute, burying
     * everything else. The card says it too, for as long as it lasts.
     */
    public Optional<RunState> survey(Path file) {
        try {
            Optional<RunState> state = load(file);
            REPORTED.remove(file);
            return state;
        } catch (UnreadableState e) {
            if (!e.getMessage().equals(REPORTED.put(file, e.getMessage()))) {
                System.err.println(e.getMessage());
            }
            return Optional.empty();
        }
    }

    /** The last unreadable-file report per file, so the same one is not printed again. */
    private final Map<Path, String> REPORTED = new ConcurrentHashMap<>();

    /**
     * The state to work from once a repository's lock is held.
     *
     * <p>Both runners read a project's state to decide whether it may run, and take the lock only
     * afterwards, on a worker. If another Forge process finished a step and let go in between, the
     * snapshot is behind the file — and advancing it wrote the old state over the new one: sessions
     * uncounted, a handoff gone, a finished turn "recovered" and committed a second time. The
     * snapshot is kept when nobody has written since, because it may carry an explicit Start's
     * override; otherwise the file wins.
     */
    public RunState latest(Path file, RunState snapshot) {
        RunState onDisk = load(file).orElse(null);
        if (onDisk == null || Objects.equals(onDisk.getUpdatedAt(), snapshot.getUpdatedAt())) {
            return snapshot;
        }
        return onDisk;
    }

    /** Stamps {@code updatedAt} and persists. Called after every phase transition. */
    public void save(Path file, RunState state) {
        state.setUpdatedAt(Instant.now());
        try {
            JsonFile.write(file, JSON.writeValueAsString(state));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write run state " + file, e);
        }
    }
}
