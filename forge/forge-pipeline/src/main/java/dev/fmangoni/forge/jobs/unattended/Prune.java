package dev.fmangoni.forge.jobs.unattended;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Reclaims disk by deleting build output from projects that are finished with.
 *
 * <p>Deliberately narrow. It touches only well-known build directories, only inside runs that have
 * reached a terminal phase, and only after an age threshold — the git history, the evidence and the
 * report always survive, so a pruned project can still be inspected and rebuilt. This is a
 * destructive operation running unattended on a machine with the user's real repositories nearby,
 * and its blast radius is the part worth being conservative about.
 */
@UtilityClass
public class Prune {

    private final List<String> BUILD_DIRS = List.of("target", "node_modules", "build", ".gradle");

    /**
     * @param minimumAge how long a run must have been finished before its build output goes
     * @return bytes reclaimed
     */
    public long run(Path runsDir, Duration minimumAge) {
        if (!Files.isDirectory(runsDir)) {
            return 0;
        }
        long freed = 0;
        try (var runs = Files.list(runsDir)) {
            for (Path run : runs.filter(Files::isDirectory).toList()) {
                freed += pruneRun(new RunPaths(run), minimumAge);
            }
        } catch (IOException e) {
            return freed;
        }
        return freed;
    }

    private long pruneRun(RunPaths paths, Duration minimumAge) {
        // Survey: one unreadable state file used to throw out of the loop and prune nothing else.
        RunState state = RunStore.survey(paths.stateFile()).orElse(null);
        if (state == null || !state.getPhase().terminal() || tooRecent(state, minimumAge)) {
            return 0;
        }
        long freed = 0;
        for (String name : BUILD_DIRS) {
            freed += deleteTree(paths.repo().resolve(name));
        }
        return freed;
    }

    private boolean tooRecent(RunState state, Duration minimumAge) {
        Instant updated = state.getUpdatedAt();
        return updated == null || updated.isAfter(Instant.now().minus(minimumAge));
    }

    /**
     * Deletes a build directory, and never anything a link inside it points at.
     *
     * <p>This used {@code Files.walk}, which on Windows goes straight through an NTFS junction.
     * Measured on this machine, JDK 24: a junction reports itself as a directory that is not a
     * symbolic link, the walk lists the files on the far side, and deleting them deletes the
     * originals. {@code node_modules} is exactly where junctions live — npm workspaces link each
     * package back to its source folder in the repository, {@code npm link} and pnpm link to
     * packages anywhere on the disk — so pruning a finished project's build output could delete
     * that project's source, or someone else's, unattended.
     *
     * <p>A link is removed as a link: deleting a junction removes the reparse point and leaves its
     * target alone, which was checked the same way.
     */
    private long deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] freed = {0};
        try {
            if (isLink(dir)) {
                Files.delete(dir);
                return 0;
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return 0;
            }
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
                        throws IOException {
                    // A junction arrives here looking like a directory. Remove the link, do not
                    // go in.
                    if (!path.equals(dir) && (attrs.isOther() || attrs.isSymbolicLink())) {
                        Files.delete(path);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                        throws IOException {
                    freed[0] += attrs.isRegularFile() ? attrs.size() : 0;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException failure)
                        throws IOException {
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return freed[0];
        }
        return freed[0];
    }

    /** A symbolic link or a junction, judged without following it. */
    private boolean isLink(Path path) throws IOException {
        BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isSymbolicLink() || attrs.isOther();
    }
}
