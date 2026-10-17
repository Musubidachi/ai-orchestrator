package dev.fmangoni.forge.report.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Evidence sorted into the sessions that produced it.
 *
 * <p>Artefacts used to be overwritten every gate, which made "has this changed?" unanswerable — the
 * only copy was always the newest one. Keeping them per session answers it, but it also means a
 * project that ran eight times has eight sets, and rendering all of them inline turns the report
 * into a scroll of near-identical screenshots.
 *
 * <p>So the newest set is shown and the rest are listed. That matches how the page is read: you
 * look at the current state, and go back through the history only when something looks wrong.
 */
@UtilityClass
public class EvidenceGroups {

    /** Oldest first, so the last element is what the report shows. */
    public List<EvidenceGroup> of(Path evidenceDir) {
        if (!Files.isDirectory(evidenceDir)) {
            return List.of();
        }
        Map<String, List<Path>> byLabel = new LinkedHashMap<>();
        try (var tree = Files.walk(evidenceDir)) {
            tree.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> byLabel
                            .computeIfAbsent(labelOf(evidenceDir, file), key -> new ArrayList<>())
                            .add(file));
        } catch (IOException e) {
            return List.of();
        }

        List<EvidenceGroup> groups = new ArrayList<>();
        byLabel.forEach((label, files) -> groups.add(new EvidenceGroup(label, files)));
        groups.sort(Comparator.comparingInt(group -> sessionNumber(group.label())));
        return groups;
    }

    private String labelOf(Path evidenceDir, Path file) {
        Path relative = evidenceDir.relativize(file);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
    }

    /**
     * Sorts {@code session-10} after {@code session-9} rather than before it.
     *
     * <p>Anything unrecognised sorts first, which puts the pre-session artefacts of older runs at
     * the start of the history where they belong.
     */
    private int sessionNumber(String label) {
        if (!label.startsWith("session-")) {
            return -1;
        }
        try {
            return Integer.parseInt(label.substring("session-".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
