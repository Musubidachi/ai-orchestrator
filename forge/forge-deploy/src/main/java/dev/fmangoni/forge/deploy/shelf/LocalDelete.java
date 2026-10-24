package dev.fmangoni.forge.deploy.shelf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deletes what a shelf has safely put on GitHub, and says what it could not.
 *
 * <p>Only ever called once GitHub holds the shelf at the commit that was pushed. Git marks its own
 * object files read-only, which Windows then refuses to delete, so that is cleared first. Links are
 * deleted as links: a junction in {@code node_modules} is never followed into whatever it points at.
 */
final class LocalDelete {

    private LocalDelete() {
    }

    /** Deletes a folder and everything in it. @return what is still there */
    static List<Path> tree(Path root) {
        List<Path> left = new ArrayList<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return left;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                delete(path, left);
            }
        } catch (IOException | RuntimeException e) {
            left.add(root);
        }
        return left;
    }

    /** Deletes everything in a folder except the entries named. @return what is still there */
    static List<Path> allBut(Path dir, Set<String> keep) {
        List<Path> left = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return left;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                if (!keep.contains(entry.getFileName().toString())) {
                    left.addAll(tree(entry));
                }
            }
        } catch (IOException e) {
            left.add(dir);
        }
        return left;
    }

    private static void delete(Path path, List<Path> left) {
        try {
            DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos != null && dos.readAttributes().isReadOnly()) {
                dos.setReadOnly(false);
            }
            Files.delete(path);
        } catch (IOException e) {
            left.add(path);
        }
    }
}
