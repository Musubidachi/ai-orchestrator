package dev.fmangoni.forge.tour.read;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import dev.fmangoni.forge.tour.read.model.ParsedType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.experimental.UtilityClass;

/**
 * Reads one Java file with the compiler's own parser.
 *
 * <p>The parser ships with the JDK, so this needs no library and cannot disagree with the language:
 * where a hand-written scan of braces guesses at generics, lambdas and text blocks and eventually
 * gets one wrong, {@code javac} already knows. Parsing only — nothing is resolved or compiled, so a
 * file whose dependencies are not on hand still reads fine, which is the normal case when touring a
 * project that has not been built.
 *
 * <p>Never throws. A file that will not parse is one the tour passes over, because half a tour of a
 * project mid-edit is worth more than an error.
 */
@UtilityClass
public class JavaSource {

    /** The type declared in {@code file}, with its methods and the file's own lines. */
    public Optional<ParsedType> parse(Path file) {
        List<String> lines = lines(file);
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Optional.empty();
        }
        // UTF-8 explicitly: the default is the platform charset, which on Windows turns every em
        // dash in a javadoc note into a question mark on its way to being read out loud.
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(file);
            JavacTask task =
                    (JavacTask) compiler.getTask(null, files, diagnostic -> {}, List.of(), null, units);
            return firstType(task, file, lines);
        } catch (IOException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private Optional<ParsedType> firstType(JavacTask task, Path file, List<String> lines)
            throws IOException {
        for (CompilationUnitTree unit : task.parse()) {
            Collector collector = new Collector(unit, Trees.instance(task));
            collector.scan(unit, null);
            if (collector.name() != null) {
                return Optional.of(new ParsedType(
                        collector.name(),
                        collector.kind(),
                        collector.doc(),
                        packageOf(unit),
                        file.getFileName().toString(),
                        lines,
                        collector.methods()));
            }
        }
        return Optional.empty();
    }

    private String packageOf(CompilationUnitTree unit) {
        return unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }

    private List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}
