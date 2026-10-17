package dev.fmangoni.forge.spec.rank;

import dev.fmangoni.forge.spec.SpecException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Rewrites a spec's rank in place, touching nothing else.
 *
 * <p>Deliberately a line edit rather than a load-and-re-serialise. Round-tripping through
 * {@link SpecWriter} would produce valid YAML but would also discard every comment the author
 * wrote, and renumbering is a bookkeeping operation — silently stripping the notes beside someone's
 * acceptance commands is a poor trade for tidier output.
 */
@UtilityClass
public class RankWriter {

    private final String KEY = "priority:";

    /**
     * Writes a whole ranking, skipping specs whose rank is already correct.
     *
     * @return how many files changed
     */
    public int apply(List<RankAssignment> ranking) {
        int changed = 0;
        for (RankAssignment assignment : ranking) {
            if (assignment.spec().priority() != assignment.rank()) {
                setRank(assignment.spec().source(), assignment.rank());
                changed++;
            }
        }
        return changed;
    }

    /** Sets {@code priority} in the file's front matter, adding the line if it is absent. */
    public void setRank(Path file, int rank) {
        List<String> lines = new ArrayList<>(read(file));
        int fenceStart = indexOfFence(lines, 0);
        int fenceEnd = fenceStart < 0 ? -1 : indexOfFence(lines, fenceStart + 1);
        if (fenceStart < 0 || fenceEnd < 0) {
            throw new SpecException(file, "no front matter block to rank");
        }

        int existing = indexOfKey(lines, fenceStart + 1, fenceEnd);
        if (existing >= 0) {
            lines.set(existing, KEY + " " + rank);
        } else {
            // Just inside the fence, where it reads as part of the header rather than buried
            // among the acceptance commands.
            lines.add(fenceStart + 1, KEY + " " + rank);
        }
        write(file, lines);
    }

    private int indexOfFence(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfKey(List<String> lines, int from, int to) {
        for (int i = from; i < to; i++) {
            if (lines.get(i).stripLeading().startsWith(KEY)) {
                return i;
            }
        }
        return -1;
    }

    private List<String> read(Path file) {
        try {
            return new ArrayList<>(List.of(
                    Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private void write(Path file, List<String> lines) {
        try {
            Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
