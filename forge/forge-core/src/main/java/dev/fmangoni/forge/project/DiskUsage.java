package dev.fmangoni.forge.project;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much disk a run occupies, measured at most once a minute.
 *
 * <p>This was a full walk of every file in every run, on every call to the job list — which the
 * dashboard makes every four seconds. MeasuredSize on this machine: the walk over 6,155 files took about
 * 500 ms, nearly all of the endpoint's 550. It grows with the file count, and a single ordinary
 * {@code node_modules} holds tens of thousands of files, so an idle dashboard was on course to spend
 * most of each interval re-reading the disk to report a number that changes a few times a day.
 *
 * <p>It also followed NTFS junctions, as {@code Files.walk} does on Windows, so it could count
 * files outside the project and would loop on a junction that points back up the tree.
 */
public final class DiskUsage {

    /** A figure this old is still worth showing; disk usage is not a live gauge. */
    static final Duration FRESH = Duration.ofSeconds(60);

    private static final Map<Path, MeasuredSize> CACHE = new ConcurrentHashMap<>();

    private DiskUsage() {
    }

    public static long of(Path root) {
        Path key = root.toAbsolutePath().normalize();
        MeasuredSize known = CACHE.get(key);
        Instant now = Instant.now();
        if (known != null && known.at().plus(FRESH).isAfter(now)) {
            return known.bytes();
        }
        long bytes = measure(key);
        CACHE.put(key, new MeasuredSize(now, bytes));
        return bytes;
    }

    /** Every regular file under the root, without going through links. */
    static long measure(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] bytes = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && (attrs.isOther() || attrs.isSymbolicLink())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    bytes[0] += attrs.isRegularFile() ? attrs.size() : 0;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A file locked by a running build is common and not worth failing the figure.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return bytes[0];
        }
        return bytes[0];
    }
}
