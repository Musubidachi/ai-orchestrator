package dev.fmangoni.forge.design;

import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Gives a designing vendor a copy of the project to look at.
 *
 * <p>A copy, because the vendor works in a folder of its own and is never handed the repository:
 * what it can read here it cannot change there. Source and screenshots only, and capped — the
 * point is for it to see what the program's screen really contains, not to hand it a
 * {@code node_modules}.
 *
 * <p>Screenshots, not pictures in general. They come from where a project's own screenshots are
 * kept — its {@code artifacts/} folder and the newest evidence the gate collected — because the
 * first pictures found anywhere in a repository are as likely to be test inputs: for a project that
 * reconstructs shapes from photographs, that was a folder of photographs of the subject.
 */
@UtilityClass
class References {

    private final Set<String> SKIPPED_FOLDERS = Set.of(".git", "node_modules", "target", "dist", "build",
            ".gradle", "__pycache__", ".venv", "venv", "test-results", "playwright-report", ".next");

    private final Set<String> SOURCE = Set.of("html", "css", "scss", "js", "jsx", "mjs", "ts", "tsx", "svg",
            "java", "fxml", "py", "kt", "md");

    private final Set<String> PICTURES = Set.of("png", "jpg", "jpeg");

    private final long LARGEST_FILE = 300_000;
    private final long ALL_SOURCE = 3_000_000;
    private final long LARGEST_PICTURE = 3_000_000;
    private final int MOST_PICTURES = 12;

    /** Copies the project's source and its screenshots under {@code into}, which is created. */
    void copy(RunPaths paths, Path into) {
        try {
            source(paths.repo(), into.resolve("source"));
            screens(screenshots(paths), into.resolve("screens"));
            attached(paths.repo().resolve(ATTACHED), into.resolve("source").resolve(ATTACHED));
        } catch (IOException e) {
            // A thinner reference is a worse brief, not a failed one: the spec is in the prompt.
            System.err.println("could not copy all of " + paths.repo() + " for the designers: " + e.getMessage());
        }
    }

    private void source(Path repo, Path into) throws IOException {
        if (!Files.isDirectory(repo)) {
            return;
        }
        long[] copied = {0};
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return SKIPPED_FOLDERS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && SOURCE.contains(extensionOf(file)) && attrs.size() <= LARGEST_FILE
                        && copied[0] + attrs.size() <= ALL_SOURCE) {
                    copied[0] += attrs.size();
                    place(file, into.resolve(repo.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Where pictures a person pasted into a note are kept in the repository: {@code Attachments.FOLDER}
     * in the jobs layer, which this module may not import. A note names them by that path, so they are
     * copied to the same place under {@code reference/source/}, and {@code DesignBrief} says so.
     */
    static final String ATTACHED = ".forge-images";

    private void attached(Path from, Path into) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (var pictures = Files.list(from)) {
            for (Path picture : pictures.filter(Files::isRegularFile).toList()) {
                if (Files.size(picture) <= LARGEST_ATTACHED) {
                    place(picture, into.resolve(picture.getFileName()));
                }
            }
        }
    }

    /** Pictures a person attached are what they want seen; the cap only keeps out what is no screenshot. */
    private final long LARGEST_ATTACHED = 10_000_000;

    /** The project's own screenshots: its {@code artifacts/} folder, then the newest evidence. */
    private List<Path> screenshots(RunPaths paths) throws IOException {
        List<Path> found = new ArrayList<>(picturesIn(paths.repo().resolve("artifacts"), 1));
        Optional<Path> newest = newestSession(paths.evidence());
        if (newest.isPresent()) {
            found.addAll(picturesIn(newest.get(), Integer.MAX_VALUE));
        }
        return found;
    }

    private void screens(List<Path> pictures, Path into) throws IOException {
        int placed = 0;
        for (Path picture : pictures) {
            if (placed >= MOST_PICTURES) {
                return;
            }
            Path target = into.resolve(picture.getFileName());
            if (!Files.exists(target)) {
                place(picture, target);
                placed++;
            }
        }
    }

    private List<Path> picturesIn(Path dir, int depth) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir, depth)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> PICTURES.contains(extensionOf(file)))
                    .filter(file -> sizeOf(file) <= LARGEST_PICTURE)
                    .sorted()
                    .toList();
        }
    }

    /** The evidence of the latest gate, by session number rather than by name, so 10 comes after 9. */
    private Optional<Path> newestSession(Path evidence) throws IOException {
        if (!Files.isDirectory(evidence)) {
            return Optional.empty();
        }
        try (Stream<Path> sessions = Files.list(evidence)) {
            return sessions.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().matches("session-\\d+"))
                    .max(Comparator.comparingInt(dir -> Integer.parseInt(dir.getFileName().toString().substring(8))));
        }
    }

    private void place(Path file, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private String extensionOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
