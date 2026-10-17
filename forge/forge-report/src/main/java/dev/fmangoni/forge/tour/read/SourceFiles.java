package dev.fmangoni.forge.tour.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * The main sources of one module, in the order they should be visited.
 *
 * <p>Package by package, and inside a package alphabetically, because a tour that jumps between
 * unrelated corners of a module is a tour nobody follows. Test sources are left out: the tour
 * explains what the project does, and the tests are how it is held to that, which is a different
 * walk.
 */
@UtilityClass
public class SourceFiles {

    /** Every {@code .java} under the module's main sources, excluding {@code package-info}. */
    public List<Path> mainJava(Path module) {
        Path root = module.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("package-info.java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The package note beside a source file, unread by the compiler but written for a reader. */
    public String packageNote(Path javaFile) {
        Path note = javaFile.resolveSibling("package-info.java");
        try {
            return Files.isRegularFile(note) ? Files.readString(note) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
