package dev.fmangoni.forge.review;

import dev.fmangoni.forge.review.model.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A note on each source file longer than a project's files should be, added to every review.
 *
 * <p>The house rules ask for small classes with one responsibility each, and Forge holds its own code
 * to that with a build test; nothing held the projects to it. 001's {@code FieldUsageAnalyzer} grew
 * to about 585 lines doing three jobs, and past the size a review can carry whole. Counted here
 * rather than left to the reviewer, so it is the same answer every round and costs no quota. A note
 * and never blocking: working code is not sent back for its shape unless the person says so, which
 * the review form lets them do.
 *
 * <p>Tests are left out — a long suite of short, independent tests is not the same problem — and so
 * is anything that is not hand-written source: generated output, lock files, minified bundles, data.
 */
public final class LongFiles {

    /** Lines past which a source file is noted. Forge's own largest is about 290. */
    public static final int LIMIT = 400;

    private static final Set<String> SOURCE = Set.of(
            "java", "kt", "kts", "scala", "groovy", "py", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue",
            "svelte", "go", "rs", "rb", "php", "cs", "c", "h", "cpp", "hpp", "cc", "swift", "dart", "css",
            "scss", "sass", "less", "html", "sh", "ps1", "cbl", "cob");

    private static final Pattern TEST = Pattern.compile(
            "(^|/)(src/test|tests?|e2e|__tests__|spec|specs|test-utils)/"
                    + "|[._-](test|spec|tests)\\.[a-z]+$|(^|/)test_[^/]*$|Tests?\\.(java|kt|cs|scala)$");

    private static final Pattern GENERATED = Pattern.compile(
            "(^|/)(node_modules|dist|build|target|out|vendor|generated|\\.[^/]+)/|\\.min\\.[a-z]+$");

    private LongFiles() {
    }

    /** A note per long source file among {@code files} (paths relative to {@code repo}), longest first. */
    public static List<Finding> notes(Path repo, List<String> files) {
        Map<Finding, Integer> lengths = new LinkedHashMap<>();
        for (String name : files) {
            String path = name.replace('\\', '/');
            if (!isSource(path)) {
                continue;
            }
            int lines = lines(repo.resolve(path));
            if (lines > LIMIT) {
                lengths.put(note(path, lines), lines);
            }
        }
        List<Finding> notes = new ArrayList<>(lengths.keySet());
        notes.sort(Comparator.comparing(lengths::get, Comparator.reverseOrder()));
        return notes;
    }

    static boolean isSource(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || TEST.matcher(path).find() || GENERATED.matcher(path).find()) {
            return false;
        }
        return SOURCE.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static int lines(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            try (var stream = Files.lines(file)) {
                return (int) stream.count();
            }
        } catch (IOException | UncheckedIOException e) {
            // Not text this can read (MalformedInputException among them): not hand-written source.
            return 0;
        }
    }

    private static Finding note(String path, int lines) {
        Finding note = new Finding();
        note.setSeverity("note");
        note.setFile(path);
        note.setStakes("upkeep");
        note.setSummary(path + " is " + lines + " lines; split it by responsibility (aim for under " + LIMIT + ")");
        note.setDetail("The house rules ask for small classes with one responsibility each. A file this long "
                + "usually holds several jobs, is harder to change safely, and is longer than a review can "
                + "read whole. Split it along the jobs it does, keeping behaviour and tests unchanged. Counted "
                + "by Forge, not raised by the reviewer.");
        note.setPlain("One file has grown to " + lines + " lines. It works, but it will be harder to fix and "
                + "check later; sending this back asks for it to be split up without changing what it does.");
        return note;
    }
}
