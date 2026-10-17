package dev.fmangoni.forge.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Forge packages each package uses, as its imports say and as its documentation says.
 *
 * <p>Package names are written short, relative to {@code dev.fmangoni.forge}: {@code run},
 * {@code security}. Both readings are plain text over the sources, like {@link SourceRules}.
 */
final class PackageGraph {

    static final String BASE = "dev.fmangoni.forge.";

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?dev\\.fmangoni\\.forge\\.((?:[a-z]\\w*\\.)*[a-z]\\w*)\\.[A-Z]",
            Pattern.MULTILINE);

    /**
     * Everything after {@code Depends on:} up to the period that ends the line.
     *
     * <p>Not "up to the first period", which is what this used to say: sub-package names contain
     * dots, so it read {@code agent.ledger, agent.model} as {@code agent} and quietly ignored the
     * rest — a rule that passed while checking almost nothing. A wrapped line continues with a
     * comma, so the first period at the end of a line is the real end of the list.
     */
    private static final Pattern DEPENDS_ON =
            Pattern.compile("Depends on:\\s*(.*?)\\.(?=\\s*(?:\\*/|\\n|$))", Pattern.DOTALL);

    private static final Pattern JAVADOC_LINE_BREAK = Pattern.compile("\\s*\\n\\s*\\*");

    private PackageGraph() {
    }

    /** Every package under the main sources, with the other Forge packages its files import. */
    static Map<String, Set<String>> imports(List<Path> mainRoots) {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Path file : SourceRules.javaFiles(mainRoots)) {
            String source = SourceRules.read(file);
            String pkg = shortName(source);
            Set<String> used = graph.computeIfAbsent(pkg, p -> new TreeSet<>());
            Matcher m = IMPORT.matcher(source);
            while (m.find()) {
                if (!m.group(1).equals(pkg)) {
                    used.add(m.group(1));
                }
            }
        }
        return graph;
    }

    /** The package a source file belongs to, written short. */
    static String shortName(String source) {
        Matcher m = PACKAGE.matcher(source);
        if (!m.find()) {
            return "";
        }
        String name = m.group(1);
        return name.startsWith(BASE) ? name.substring(BASE.length()) : name;
    }

    /**
     * The {@code package-info.java} of a package, which may not exist yet: in whichever module the
     * package's sources are.
     */
    static Path packageInfo(List<Path> mainRoots, String pkg) {
        String folder = BASE.replace('.', '/') + pkg.replace('.', '/');
        return mainRoots.stream()
                .map(root -> root.resolve(folder))
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(mainRoots.getFirst().resolve(folder))
                .resolve("package-info.java");
    }

    /**
     * The packages a {@code package-info.java} declares with its {@code Depends on:} line, or empty
     * when the file or the line is missing.
     */
    static Optional<Set<String>> declared(Path packageInfo) {
        if (!Files.isRegularFile(packageInfo)) {
            return Optional.empty();
        }
        return dependsOn(SourceRules.read(packageInfo));
    }

    /**
     * Reads {@code Depends on: a, b.}, which may wrap onto further Javadoc lines and ends at its period;
     * {@code Depends on: nothing.} declares no dependency.
     */
    static Optional<Set<String>> dependsOn(String packageInfoSource) {
        Matcher m = DEPENDS_ON.matcher(packageInfoSource);
        if (!m.find()) {
            return Optional.empty();
        }
        Set<String> names = new TreeSet<>();
        for (String name : JAVADOC_LINE_BREAK.matcher(m.group(1)).replaceAll(" ").split(",")) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty() && !trimmed.equals("nothing")) {
                names.add(trimmed);
            }
        }
        return Optional.of(names);
    }

    /** One dependency cycle, as the packages around it, or an empty list when there is none. */
    static List<String> cycle(Map<String, Set<String>> graph) {
        Set<String> finished = new TreeSet<>();
        for (String start : new TreeSet<>(graph.keySet())) {
            List<String> found = cycleFrom(start, graph, new ArrayList<>(), finished);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private static List<String> cycleFrom(String pkg, Map<String, Set<String>> graph, List<String> path,
            Set<String> finished) {
        int seen = path.indexOf(pkg);
        if (seen >= 0) {
            List<String> loop = new ArrayList<>(path.subList(seen, path.size()));
            loop.add(pkg);
            return loop;
        }
        if (finished.contains(pkg)) {
            return List.of();
        }
        path.add(pkg);
        for (String next : new TreeSet<>(graph.getOrDefault(pkg, Set.of()))) {
            List<String> found = cycleFrom(next, graph, path, finished);
            if (!found.isEmpty()) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        finished.add(pkg);
        return List.of();
    }
}
