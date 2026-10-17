package dev.fmangoni.forge.tour.read;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The child projects of a Maven parent, in the order the parent lists them.
 *
 * <p>Read from the {@code <modules>} block rather than by looking for directories with a pom in
 * them: the parent's order is the order someone decided the build should go in, which is usually
 * bottom-up, which is the order a tour should visit them in too.
 *
 * <p>Nested parents are followed, so a parent of parents tours the leaves. A module listed but
 * missing from disk is skipped rather than raised — this tool explains a project, and refusing to
 * say anything because one module moved would be the wrong trade.
 */
@UtilityClass
public class MavenParent {

    private final Pattern MODULE = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

    /** Every leaf module under {@code pom}, depth first, or the project itself if it lists none. */
    public List<Path> modulesOf(Path pom) {
        List<Path> found = new ArrayList<>();
        collect(pom, found, 0);
        return found;
    }

    /** The directory a pom sits in, whether the path given was the pom or the folder. */
    public Path directoryOf(Path pom) {
        return Files.isDirectory(pom) ? pom : pom.getParent();
    }

    /** The pom for a path that may be either the pom itself or the directory holding it. */
    public Path pomIn(Path path) {
        return Files.isDirectory(path) ? path.resolve("pom.xml") : path;
    }

    private void collect(Path pom, List<Path> found, int depth) {
        // A parent that somehow lists itself, or a loop between two of them, would otherwise
        // recurse until the stack gives out.
        if (depth > 5) {
            return;
        }
        Path file = pomIn(pom);
        if (!Files.isRegularFile(file)) {
            // Listed by its parent, but with no pom of its own. Its sources are still what the
            // parent says the project is made of, so they are toured rather than dropped without
            // a word — which is the failure mode that loses a module and looks like a short tour.
            if (depth > 0 && Files.isDirectory(pom)) {
                found.add(pom);
            }
            return;
        }
        Path directory = file.getParent();
        List<String> names = namesIn(file);
        if (names.isEmpty()) {
            found.add(directory);
            return;
        }
        for (String name : names) {
            Path child = directory.resolve(name);
            if (Files.isDirectory(child)) {
                collect(child, found, depth + 1);
            }
        }
    }

    private List<String> namesIn(Path pom) {
        String text = read(pom);
        List<String> names = new ArrayList<>();
        Matcher matcher = MODULE.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private String read(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
