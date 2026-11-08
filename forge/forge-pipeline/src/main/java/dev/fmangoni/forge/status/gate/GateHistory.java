package dev.fmangoni.forge.status.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The gates a version went through, oldest first: {@code gates.json} in its run folder.
 *
 * <p>The state keeps only the last gate, so a panel could say neither which session that gate
 * followed while the next one runs nor what the gates before it did. The outline notes each gate
 * here as it finishes, worked out the way the panel works out the last one ({@link GateSummary}).
 *
 * <p>Beside the state rather than in it, like {@code pause.json}: nothing decides anything from it,
 * and a gate it fails to note costs a line on a panel, never the run.
 */
@UtilityClass
public class GateHistory {

    static final String FILE = "gates.json";

    /** Enough for every gate of a long version; the oldest go first. */
    static final int KEPT = 40;

    private static final ObjectMapper JSON = Json.mapper();

    /**
     * Notes the gate that has just run on a version, from what it left on the state.
     *
     * <p>A session number lower than the last one noted means the version started over, so what
     * came before is dropped.
     */
    public void note(Path runRoot, List<String> acceptance, RunState state) {
        try {
            LastGate gate = GateSummary.of(acceptance, state);
            if (!gate.ran()) {
                return;
            }
            List<PastGate> past = new ArrayList<>(load(runRoot));
            PastGate now = PastGate.of(gate, state.getSessions(), Instant.now());
            if (!past.isEmpty() && past.getLast().session() > now.session()) {
                past.clear();
            }
            past.add(now);
            List<PastGate> kept = past.subList(Math.max(0, past.size() - KEPT), past.size());
            JsonFile.write(runRoot.resolve(FILE), JSON.writeValueAsString(kept));
        } catch (IOException | RuntimeException e) {
            System.err.println("could not note the gate in " + runRoot.resolve(FILE) + ": " + e.getMessage());
        }
    }

    /** The gates noted for a version, oldest first; quietly none when there is no file or it cannot be read. */
    public List<PastGate> load(Path runRoot) {
        Path file = runRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            PastGate[] read = JSON.readValue(JsonFile.read(file), PastGate[].class);
            return read == null ? List.of() : List.of(read);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
