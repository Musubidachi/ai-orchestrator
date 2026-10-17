package dev.fmangoni.forge.security.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the security check found on one commit, persisted as {@code security/security.json}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityReport {

    /** The commit that was checked, so a report on older code is recognisable as such. */
    private String commit = "";

    /** The tree that was checked; unchanged code keeps the same tree across empty commits. */
    private String tree = "";

    private Instant checkedAt;

    private List<LayerResult> layers = new ArrayList<>();

    public SecurityReport(String commit, String tree, List<LayerResult> layers) {
        this.commit = commit == null ? "" : commit;
        this.tree = tree == null ? "" : tree;
        this.checkedAt = Instant.now();
        this.layers = new ArrayList<>(layers);
    }

    /** Every finding, most severe first. */
    @JsonIgnore
    public List<SecurityFinding> findings() {
        return layers.stream()
                .flatMap(layer -> layer.findings().stream())
                .sorted(Comparator.comparingInt(SecurityFinding::rank))
                .toList();
    }

    /** What earns a repair turn, and a ruling if it survives one. */
    @JsonIgnore
    public List<SecurityFinding> serious() {
        return findings().stream().filter(SecurityFinding::serious).toList();
    }

    /**
     * What a person has to rule on before accepting.
     *
     * <p>Serious findings, plus any critical or high secret — including one only in history, which
     * no repair turn can fix because the key has to be revoked by whoever owns it.
     */
    @JsonIgnore
    public List<SecurityFinding> forRuling() {
        return findings().stream()
                .filter(f -> f.serious() || (SecurityFinding.SECRETS_LAYER.equals(f.layer()) && f.rank() <= 1))
                .toList();
    }

    /** Findings per severity, for a card: {@code {"high": 2, "low": 5}}. */
    @JsonIgnore
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new TreeMap<>(Comparator.comparingInt(
                severity -> new SecurityFinding("", severity, "", "", "", false).rank()));
        findings().forEach(finding -> counts.merge(finding.severity(), 1, Integer::sum));
        return counts;
    }

    public static SecurityReport load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.mapper().readValue(JsonFile.read(file), SecurityReport.class);
        } catch (IOException e) {
            // Unreadable is treated as never checked: the check can simply run again.
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
