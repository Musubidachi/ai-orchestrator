package dev.fmangoni.forge.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Java source as text for the structural rules {@link ArchitectureTest} enforces.
 *
 * <p>Deliberately simple and dependency-free: the rules are about how the code is laid out on disk,
 * and a line-based reading of it is enough to hold them. String literals and text blocks are masked
 * out first, so prompt text that mentions a class name is not mistaken for code.
 */
final class SourceRules {

    private static final Pattern QUALIFIED = Pattern.compile(
            "(?<![\\w.])((?:dev\\.fmangoni\\.forge|java|javax|com\\.fasterxml|org\\.junit|com\\.sun|lombok)(?:\\.[a-z]\\w*)*)\\.([A-Z]\\w*)");

    private static final Pattern DECLARED = Pattern.compile("\\b(?:class|record|interface|enum)\\s+([A-Z]\\w*)");

    private SourceRules() {
    }

    /** Every {@code .java} file under the given source roots that exist. */
    static List<Path> javaFiles(List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    /**
     * One source root per module, such as every {@code forge-*}/src/main/java.
     *
     * <p>Found from wherever the tests run: a module directory (Maven), the forge directory, or the
     * repository root. The rules are about the whole codebase, so they must see every module; a
     * rule that quietly checked only the module it sits in would pass while being broken.
     */
    static List<Path> roots(String... relatives) {
        Path forge = Stream.of(Path.of(".."), Path.of("."), Path.of("forge"))
                .filter(dir -> Files.isDirectory(dir.resolve("forge-core")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("cannot find the forge modules from "
                        + Path.of("").toAbsolutePath()));
        try (Stream<Path> modules = Files.list(forge)) {
            return modules.filter(dir -> dir.getFileName().toString().startsWith("forge-"))
                    .flatMap(dir -> Stream.of(relatives).map(relative -> dir.resolve("src").resolve(relative)))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The code after the import block, with string literals and text blocks blanked out. */
    static String codeBody(String source) {
        String[] lines = source.split("\n", -1);
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("import ") || lines[i].startsWith("package ")) {
                start = i + 1;
            }
        }
        return maskLiterals(String.join("\n", Arrays.copyOfRange(lines, start, lines.length)));
    }

    /**
     * Fully-qualified type names written inline, as {@code package.Type}.
     *
     * <p>A name stays legitimately qualified only when a type of the same simple name is declared in
     * the same file, where an import could not disambiguate it.
     */
    static List<String> inlineQualifiedNames(String source) {
        String body = codeBody(source);
        List<String> declared = new ArrayList<>();
        Matcher d = DECLARED.matcher(body);
        while (d.find()) {
            declared.add(d.group(1));
        }
        List<String> found = new ArrayList<>();
        Matcher m = QUALIFIED.matcher(body);
        while (m.find()) {
            if (!declared.contains(m.group(2))) {
                found.add(m.group(1) + "." + m.group(2));
            }
        }
        return found;
    }

    /** The code after the import block with both string literals and comments blanked out. */
    static String plainCode(String source) {
        return maskComments(codeBody(source));
    }

    /**
     * Names of types declared inside another type in this source.
     *
     * <p>Counted by brace depth over the code with strings and comments blanked out: a declaration at
     * depth zero is the file's own type, anything deeper is nested.
     */
    static List<String> nestedTypes(String source) {
        String code = plainCode(source);
        List<String> nested = new ArrayList<>();
        Matcher m = DECLARED.matcher(code);
        while (m.find()) {
            int depth = 0;
            for (int i = 0; i < m.start(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (depth > 0) {
                nested.add(m.group(1));
            }
        }
        return nested;
    }

    private static String maskComments(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static String maskLiterals(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(out, i, end);
                i = end;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i);
                int j = i + 1;
                while (j < n && text.charAt(j) != quote && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                blank(out, i, Math.min(j + 1, n));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int from, int to) {
        for (int k = from; k < to && k < out.length(); k++) {
            if (out.charAt(k) != '\n') {
                out.setCharAt(k, ' ');
            }
        }
    }
}
