package dev.fmangoni.forge.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Packages depend one way, and each says in its own documentation what it depends on.
 *
 * <p>The {@code Depends on:} line in every {@code package-info.java} is the rule, not a comment about
 * it: this test fails when a package imports something its line does not name, when the line names
 * something no longer imported, and when the imports add up to a cycle. Letting a package use another
 * is therefore a visible edit to its documentation, never a side effect of an import.
 */
class LayersTest {

    private static final List<Path> MAIN = SourceRules.roots("main/java");

    @Test
    @DisplayName("every package explains itself in a package-info.java with a Depends on: line")
    void everyPackageDocumented() {
        List<String> missing = new ArrayList<>();
        for (String pkg : PackageGraph.imports(MAIN).keySet()) {
            if (PackageGraph.declared(PackageGraph.packageInfo(MAIN, pkg)).isEmpty()) {
                missing.add(pkg);
            }
        }
        assertEquals(List.of(), missing, "write a package-info.java for these; see forge/ARCHITECTURE.md");
    }

    @Test
    @DisplayName("each package imports exactly the packages its documentation says it depends on")
    void importsMatchDocumentation() {
        List<String> differences = new ArrayList<>();
        PackageGraph.imports(MAIN).forEach((pkg, imported) -> PackageGraph
                .declared(PackageGraph.packageInfo(MAIN, pkg))
                .ifPresent(declared -> differences.addAll(differences(pkg, imported, declared))));
        assertEquals(List.of(), differences,
                "either the import is wrong, or the package's Depends on: line needs a deliberate edit");
    }

    @Test
    @DisplayName("no package depends on itself through others")
    void layersPointDown() {
        assertEquals(List.of(), PackageGraph.cycle(PackageGraph.imports(MAIN)),
                "these packages can no longer be separated; move the type they share down a layer");
    }

    @Test
    @DisplayName("the layer rules find a cycle, an undeclared import and a stale declaration")
    void layerRulesWork() {
        Map<String, Set<String>> looped = Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a"), "d", Set.of());
        assertEquals(List.of("a", "b", "c", "a"), PackageGraph.cycle(looped));
        assertEquals(List.of(), PackageGraph.cycle(Map.of("a", Set.of("b"), "b", Set.of())));

        String info = """
                /**
                 * Runs things.
                 *
                 * <p>Depends on: json, process.
                 */
                package dev.fmangoni.forge.x;
                """;
        assertEquals(Optional.of(Set.of("json", "process")), PackageGraph.dependsOn(info));
        assertEquals(Optional.of(Set.of()), PackageGraph.dependsOn("/** Depends on: nothing. */"));
        assertEquals(Optional.empty(), PackageGraph.dependsOn("/** Runs things. */"));
        assertEquals(Optional.of(Set.of("json", "process", "spec")),
                PackageGraph.dependsOn("/**\n * <p>Depends on: json, process,\n * spec.\n */"), "a list may wrap");
        assertEquals(Optional.of(Set.of("agent.ledger", "agent.model", "spec.tier")),
                PackageGraph.dependsOn("/** Depends on: agent.ledger, agent.model, spec.tier. */"),
                "a sub-package name contains dots, and the list does not end at the first one");

        assertEquals(List.of("x imports git without declaring it", "x declares json but no longer imports it"),
                differences("x", Set.of("process", "git"), Set.of("json", "process")));
    }

    private static List<String> differences(String pkg, Set<String> imported, Set<String> declared) {
        List<String> found = new ArrayList<>();
        imported.stream().sorted().filter(p -> !declared.contains(p))
                .forEach(p -> found.add(pkg + " imports " + p + " without declaring it"));
        declared.stream().sorted().filter(p -> !imported.contains(p))
                .forEach(p -> found.add(pkg + " declares " + p + " but no longer imports it"));
        return found;
    }
}
