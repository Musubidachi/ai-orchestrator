package dev.fmangoni.forge.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How every module reads and writes JSON, swept rather than listed.
 *
 * <p>Here rather than beside {@code Json}, because the rule is about every module and only this one
 * sees them all: a sweep of its own module's sources would pass while the rest drifted.
 */
class JsonRulesTest {

    @Test
    @DisplayName("nothing that persists state builds a mapper of its own")
    void noPrivateMappers() {
        // A sweep rather than a list, so the seventeenth one fails here instead of the next time
        // somebody adds an Instant to a file whose mapper cannot write one.
        //
        // The exceptions are readers of other people's output — vendor event streams, model
        // replies, and the security tools' reports (npm audit, OSV, semgrep) — which parse and
        // never persist, so a drifting date format cannot reach a file. SecurityReport, which does
        // persist, uses Json.mapper() and is not on this list. AntigravityArgs writes the one-line
        // NDJSON message the CLI reads from stdin; the shared mapper indents, which would split it.
        Set<String> mayBuildTheirOwn = Set.of(
                "Json.java", "JsonlEvents.java", "Usage.java", "PlanParser.java",
                "ReviewParser.java", "Activity.java", "Suggester.java", "JsonText.java",
                "DependencyAudit.java", "OsvHttpClient.java", "SecurityReview.java", "StaticAnalysis.java",
                "AntigravityArgs.java");

        List<String> offenders = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java"))) {
            String name = file.getFileName().toString();
            if (!mayBuildTheirOwn.contains(name) && SourceRules.read(file).contains("new ObjectMapper()")) {
                offenders.add(name);
            }
        }
        assertEquals(List.of(), offenders, "these build their own ObjectMapper. Use Json.mapper(), or add "
                + "one to the exception list here with a reason it never writes a file.");
    }

    @Test
    @DisplayName("nothing that persists JSON writes it in place")
    void noInPlaceJsonWrites() {
        // A sweep rather than a list, like the one for ObjectMappers: the next store written
        // should fail here, not on the day a kill lands in the middle of its write.
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java"))) {
            boolean inPlace = SourceRules.read(file).lines().anyMatch(line ->
                    line.contains("Files.writeString(") && line.contains("writeValueAsString"));
            if (inPlace) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), offenders,
                "these write JSON in place. Use JsonFile.write, which a kill cannot leave half-written.");
    }

    @Test
    @DisplayName("the sweeps see every module, not just the one they sit in")
    void sweepsSeeEveryModule() {
        List<Path> roots = SourceRules.roots("main/java");
        for (String module : List.of("forge-core", "forge-security", "forge-review", "forge-deploy",
                "forge-marketing", "forge-report", "forge-pipeline", "forge-app")) {
            assertEquals(1, roots.stream().filter(root -> root.toString().contains(module)).count(),
                    module + " is missing from " + roots);
        }
    }
}
