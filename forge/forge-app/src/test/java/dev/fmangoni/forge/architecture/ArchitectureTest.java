package dev.fmangoni.forge.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the code is laid out, held by the build rather than by care.
 *
 * <p>Fabrizio wants small, separate pieces that a person — or a model — cannot quietly weave
 * together. Rules like that erode one convenient edit at a time, and each edit looks harmless on its
 * own: the last three features added 230 inline qualified names after an earlier cleanup had removed
 * them. So each rule here fails the build instead of waiting to be noticed.
 */
class ArchitectureTest {

    @Test
    @DisplayName("types are imported, not written out in full in the middle of the code")
    void noInlineQualifiedNames() {
        List<String> violations = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java", "test/java"))) {
            for (String name : SourceRules.inlineQualifiedNames(SourceRules.read(file))) {
                violations.add(file.getFileName() + ": " + name);
            }
        }
        assertEquals(List.of(), violations, "import these instead of writing them out in full");
    }

    @Test
    @DisplayName("every type in the product has a file of its own")
    void noNestedTypes() {
        // Nested types are easy to lose: they do not appear in a file listing, and a class grows a
        // second and third responsibility inside itself without anyone deciding it should. Test code
        // is exempt, because JUnit's @Nested grouping and one-off stub agents are what nesting is for.
        List<String> violations = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java"))) {
            for (String name : SourceRules.nestedTypes(SourceRules.read(file))) {
                violations.add(file.getFileName() + ": " + name);
            }
        }
        assertEquals(List.of(), violations, "move each of these into its own file");
    }

    /**
     * How many classes one package may hold.
     *
     * <p>Eight is not a law of nature; it is the size at which the packages here stopped being
     * lists and started being subjects. What the number is for is the decision: a ninth class means
     * either the package covers two things, or it covers one bigger thing than it used to, and both
     * of those are worth a moment's thought rather than an accumulation nobody chose.
     */
    private static final int MOST_CLASSES_PER_PACKAGE = 8;

    @Test
    @DisplayName("no package quietly grows into a junk drawer")
    void packagesStaySmall() {
        // This is the rule that was missing while jobs reached 26 classes, agent 25 and ui 23. Each
        // of those grew one reasonable-looking class at a time.
        assertEquals(List.of(), oversized(classesPerPackage(), MOST_CLASSES_PER_PACKAGE),
                "split these by subject, or raise the cap here on purpose");
    }

    @Test
    @DisplayName("the size rule counts the classes in a package, and ignores its package-info")
    void sizeRuleWorks() {
        assertEquals(List.of("jobs has 9, the most is 8"),
                oversized(Map.of("jobs", 9, "git", 8, "json", 3), 8));
        assertEquals(List.of(), oversized(Map.of("jobs", 8), 8));

        Map<String, Integer> counted = classesPerPackage();
        assertTrue(counted.getOrDefault("json", 0) >= 3, "expected the real packages to be counted: " + counted);
        assertTrue(counted.values().stream().noneMatch(n -> n == 0), "package-info alone is not a class");
    }

    private static List<String> oversized(Map<String, Integer> counts, int cap) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > cap)
                .map(entry -> entry.getKey() + " has " + entry.getValue() + ", the most is " + cap)
                .sorted()
                .toList();
    }

    private static Map<String, Integer> classesPerPackage() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java"))) {
            if (!file.getFileName().toString().equals("package-info.java")) {
                counts.merge(PackageGraph.shortName(SourceRules.read(file)), 1, Integer::sum);
            }
        }
        return counts;
    }

    @Test
    @DisplayName("no Lombok builder on a record, which Eclipse cannot generate")
    void noBuilderOnRecords() {
        // Lombok builds a record under javac and not under Eclipse's compiler, so this compiles,
        // ships, and shows up as "the constructor is undefined" in the IDE — which is where the
        // six status groups were found. Records for data, @Value classes when a builder is wanted:
        // RunState has always been the latter, which is why it never broke.
        List<String> violations = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java"))) {
            if (builtRecord(SourceRules.read(file))) {
                violations.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), violations, "make these @Value classes, or build them without Lombok");
    }

    @Test
    @DisplayName("the builder rule tells a record from a class, and ignores the word in prose")
    void builderRuleWorks() {
        assertTrue(builtRecord("@Builder\npublic record Thing(int x) {}"));
        assertTrue(builtRecord("@Builder(toBuilder = true)\npublic record Thing(int x) {}"));
        assertFalse(builtRecord("@Value\n@Builder\npublic class Thing {}"), "a class is the form that works");
        assertFalse(builtRecord("/** Mentions @Builder and a record in prose. */\npublic record Thing(int x) {}"));
    }

    private static boolean builtRecord(String source) {
        String code = SourceRules.plainCode(source);
        return code.contains("@Builder") && Pattern.compile("^\\s*public record \\w+", Pattern.MULTILINE)
                .matcher(code).find();
    }

    @Test
    @DisplayName("nothing asks a process for its descendants, which can be somebody else's")
    void noDescendantsCalls() {
        // Asked of a command that has exited, descendants() returns whatever now has the command's
        // recycled process id for a parent. Forge asks in order to kill, and what it killed was the
        // editor: started detached, its parent is a dead launcher whose id Windows hands out again.
        // ProcessTree checks start times. Tests are held to it too, because they run in the same
        // checkout as a live editor and killed it the same way.
        List<String> violations = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(SourceRules.roots("main/java", "test/java"))) {
            if (asksForDescendants(SourceRules.read(file))) {
                violations.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), violations, "use ProcessTree.members() or ProcessTree.kill() instead");
    }

    @Test
    @DisplayName("the descendants rule catches a call, and ignores the word in prose or in a string")
    void descendantsRuleWorks() {
        assertTrue(asksForDescendants("class A { void f(Process p) { p.descendants().count(); } }"));
        assertTrue(asksForDescendants("class A { long f(ProcessHandle h) { return h\n.descendants ().count(); } }"));
        assertFalse(asksForDescendants("/** Never {@code p.descendants()}. */\nclass A { String s = \".descendants()\"; }"));
        assertFalse(asksForDescendants("class A { Object descendants() { return null; } }"),
                "declaring the method, as a stand-in handle must, is not calling it");
    }

    private static boolean asksForDescendants(String source) {
        return Pattern.compile("\\.\\s*descendants\\s*\\(").matcher(SourceRules.plainCode(source)).find();
    }

    @Test
    @DisplayName("the nested-type rule finds types inside types, and not in comments, strings or the file's own type")
    void nestedTypeRuleWorks() {
        String source = """
                package dev.fmangoni.forge.x;

                /** A class Sample, with a record Hidden mentioned only in this comment. */
                public final class Sample {
                    String text = "record Fake(int x) {}";
                    private record Inner(int x) {
                    }
                    void run() {
                        // class NotReal
                    }
                }
                """;

        assertEquals(List.of("Inner"), SourceRules.nestedTypes(source));
    }

    @Test
    @DisplayName("the inline-name rule catches a qualified name, and ignores strings and same-named local types")
    void inlineNameRuleWorks() {
        String source = """
                package dev.fmangoni.forge.x;

                import java.util.List;

                class Sample {
                    java.util.Map<String, String> map;
                    String prompt = "see dev.fmangoni.forge.run.JobRunner for details";
                    record Progress(int p) {}
                    int label = dev.fmangoni.forge.run.Progress.percent();
                }
                """;

        List<String> found = SourceRules.inlineQualifiedNames(source);

        assertEquals(List.of("java.util.Map"), found, "only the real inline name is reported");
        assertTrue(SourceRules.inlineQualifiedNames("class A { java.util.List<String> x; }")
                .contains("java.util.List"));
    }
}
