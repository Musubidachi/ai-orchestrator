package dev.fmangoni.forge.report.changes;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The version of the same family a person accepted most recently, and where each run's latest
 * screenshots are.
 *
 * <p>A family is every run working in one repository: {@code runs/008} and its versions
 * {@code runs/008.1}, {@code runs/008.2}. Accepted versions keep their evidence, so nothing has to be
 * copied aside at the moment of accepting.
 */
final class LastAccepted {

    private LastAccepted() {
    }

    /** The other run of this family accepted last, by when its state was last written. */
    static Optional<Path> of(RunPaths paths) {
        Path runs = paths.root().getParent();
        Path familyRepo = paths.repo().getParent();
        if (runs == null || familyRepo == null || familyRepo.getFileName() == null) {
            return Optional.empty();
        }
        String family = familyRepo.getFileName().toString();
        try (Stream<Path> dirs = Files.list(runs)) {
            return dirs.filter(dir -> !dir.equals(paths.root()))
                    .filter(dir -> isOf(dir.getFileName().toString(), family))
                    .map(dir -> RunStore.load(dir.resolve("state.json"))
                            .filter(state -> state.getPhase() == Phase.ACCEPTED)
                            .map(state -> Map.entry(dir, when(state))))
                    .flatMap(Optional::stream)
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The newest session folder under a run's evidence that holds a screenshot. */
    static Optional<Path> latestEvidence(Path runRoot) {
        Path evidence = new RunPaths(runRoot).evidence();
        if (!Files.isDirectory(evidence)) {
            return Optional.empty();
        }
        try (Stream<Path> sessions = Files.list(evidence)) {
            List<Path> numbered = sessions.filter(dir -> session(dir) >= 0)
                    .sorted(Comparator.comparingInt(LastAccepted::session).reversed())
                    .toList();
            return numbered.stream().filter(LastAccepted::holdsPictures).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static List<Path> pictures(Path session) {
        try (Stream<Path> files = Files.walk(session)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean holdsPictures(Path session) {
        return !pictures(session).isEmpty();
    }

    private static boolean isOf(String name, String family) {
        return name.equals(family) || name.startsWith(family + ".");
    }

    private static int session(Path dir) {
        String name = dir.getFileName().toString();
        if (!name.startsWith("session-")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("session-".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Instant when(RunState state) {
        return state.getUpdatedAt() == null ? Instant.EPOCH : state.getUpdatedAt();
    }
}
