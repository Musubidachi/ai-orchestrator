package dev.fmangoni.forge.spec;

import dev.fmangoni.forge.spec.model.PictureRow;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pictures a spec names, and what is wrong with them before any agent reads it.
 *
 * <p>A spec names a picture as {@code [image: path]}, relative to the project's repository, and a
 * spec built to pictures has a table pairing each one with the screenshot the builder saves of it.
 * A picture that is not there, or a screenshot the evidence list does not ask for, was found only
 * when an agent tripped on it, which cost a turn. Read here, it costs nothing.
 */
public final class SpecPictures {

    /** Not one written inside backticks: that is a spec talking about the syntax, not naming a picture. */
    private static final Pattern IMAGE = Pattern.compile("(?<!`)\\[image:\\s*([^\\]]+?)\\s*\\]");

    /** {@code | [image: picture] | state | `screenshot` |}, as the specs' pictures tables are written. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*\\[image:\\s*([^\\]]+?)\\s*\\]\\s*\\|([^|]*)\\|\\s*`([^`]+)`\\s*\\|\\s*$",
            Pattern.MULTILINE);

    private SpecPictures() {
    }

    /** Every picture the text names, once each, in the order it names them. */
    public static List<String> named(String body) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IMAGE.matcher(body == null ? "" : body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    /** The rows of the text's pictures tables, in order. */
    public static List<PictureRow> rows(String body) {
        List<PictureRow> out = new ArrayList<>();
        Matcher m = ROW.matcher(body == null ? "" : body.replace("\r\n", "\n"));
        while (m.find()) {
            out.add(new PictureRow(m.group(1), m.group(2).trim(), m.group(3).trim()));
        }
        return out;
    }

    /**
     * What is wrong with the spec's pictures, one sentence each; empty when nothing is.
     *
     * @param repo the repository the spec's agent works in, which picture paths are relative to
     */
    public static List<String> problems(Spec spec, Path repo) {
        List<String> out = new ArrayList<>();
        for (String picture : named(spec.body())) {
            missing(picture, repo).ifPresent(out::add);
        }
        List<String> evidence = spec.evidence() == null ? List.of() : spec.evidence();
        for (PictureRow row : rows(spec.body())) {
            if (!asked(row.screenshot(), evidence)) {
                out.add("the screenshot `" + row.screenshot() + "` of " + row.picture()
                        + " is not in the evidence list, so no gate asks for it");
            }
        }
        return out;
    }

    private static Optional<String> missing(String picture, Path repo) {
        Path base = repo.toAbsolutePath().normalize();
        try {
            Path file = base.resolve(picture).normalize();
            if (!file.startsWith(base)) {
                return Optional.of("the picture " + picture + " is outside the repository");
            }
            return Files.isRegularFile(file) ? Optional.empty()
                    : Optional.of("the picture " + picture + " is not in the repository");
        } catch (InvalidPathException e) {
            return Optional.of("the picture " + picture + " is not a path");
        }
    }

    /** Whether the evidence list names this file, exactly or by a glob. */
    private static boolean asked(String screenshot, List<String> evidence) {
        for (String glob : evidence) {
            if (glob.equals(screenshot)) {
                return true;
            }
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                if (matcher.matches(Path.of(screenshot))) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // A glob or a path that cannot be read matches nothing.
            }
        }
        return false;
    }
}
