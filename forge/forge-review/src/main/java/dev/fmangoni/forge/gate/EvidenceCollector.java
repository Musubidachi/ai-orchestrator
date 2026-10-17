package dev.fmangoni.forge.gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Finds and copies the artefacts a spec nominated as evidence.
 *
 * <p>This is what turns a green tick into something a person can actually check in ten seconds:
 * the rendered PDF, the screenshot, the generated report. Files are copied rather than referenced
 * because the project's build output is pruned later to reclaim disk, while the evidence for a
 * finished run is meant to outlive it.
 */
@UtilityClass
public class EvidenceCollector {

    /** Guards against a careless glob such as {@code **} dragging an entire node_modules along. */
    private final int MAX_FILES = 50;

    private final int MAX_DEPTH = 12;

    private final List<String> SKIP = List.of(".git", "node_modules");

    /**
     * @param globs patterns relative to the project root, e.g. {@code out.html} or
     *              {@code target/screenshots/*.png}
     * @return the copies, inside {@code destination}
     */
    public List<Path> collect(Path repo, List<String> globs, Path destination) {
        List<Path> found = find(repo, globs);
        List<Path> copies = new ArrayList<>();
        try {
            for (Path file : found) {
                copies.add(copy(file, destination.resolve(repo.relativize(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot collect evidence from " + repo, e);
        }
        return copies;
    }

    /**
     * The globs the project produced nothing for.
     *
     * <p>A spec nominating a screenshot is saying that is how a human will check the work. Passing
     * the tests while producing none of it means the one thing a person could actually look at does
     * not exist — so the gate treats this as a failure rather than a missing nicety.
     */
    public List<String> unmatched(Path repo, List<String> globs) {
        // One walk for every glob. This used to search the whole project once per glob, and a spec
        // with approved features passes three per feature — fifteen walks on one project here.
        List<Path> files = filesUnder(repo);
        List<String> missing = new ArrayList<>();
        for (String glob : globs) {
            PathMatcher matcher = matcher(glob);
            if (files.stream().noneMatch(file -> matches(List.of(matcher), repo.relativize(file)))) {
                missing.add(glob);
            }
        }
        return missing;
    }

    /** Files in the project matching any of the globs. Read-only; copies nothing. */
    public List<Path> find(Path repo, List<String> globs) {
        if (globs.isEmpty() || !Files.isDirectory(repo)) {
            return List.of();
        }
        List<PathMatcher> matchers = globs.stream().map(EvidenceCollector::matcher).toList();

        List<Path> found = new ArrayList<>();
        for (Path file : filesUnder(repo)) {
            if (matches(matchers, repo.relativize(file))) {
                found.add(file);
            }
            if (found.size() >= MAX_FILES) {
                break;
            }
        }
        return found;
    }

    private PathMatcher matcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob.replace('\\', '/'));
    }

    /**
     * Every regular file an evidence glob could reasonably mean.
     *
     * <p>Skips {@code .git} and {@code node_modules} at the directory, without going in. They used to
     * be filtered out after the walk had already been through every file in them — about 700 ms per
     * gate on a small project here, repeated for each glob — and a real dependency tree is ten times
     * the size.
     *
     * <p>Does not follow links. On this JDK {@code Files.walk} goes through NTFS junctions, so a
     * glob such as {@code **}{@code /*.png} could copy files from anywhere a junction points into
     * evidence the report then serves.
     */
    private List<Path> filesUnder(Path repo) {
        if (!Files.isDirectory(repo, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(repo, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(repo)) {
                                return FileVisitResult.CONTINUE;
                            }
                            boolean link = attrs.isOther() || attrs.isSymbolicLink();
                            return link || SKIP.contains(dir.getFileName().toString())
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot search " + repo + " for evidence", e);
        }
        return files;
    }

    private boolean matches(List<PathMatcher> matchers, Path relative) {
        // Match against the forward-slash form so specs stay portable and read naturally.
        Path normalised = Path.of(relative.toString().replace('\\', '/'));
        return matchers.stream().anyMatch(m -> m.matches(normalised));
    }

    private Path copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
