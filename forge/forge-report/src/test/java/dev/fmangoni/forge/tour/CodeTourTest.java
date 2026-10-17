package dev.fmangoni.forge.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.model.Tour;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tour of a real Maven parent, built from nothing but the files in it.
 *
 * <p>The invariant worth defending is the one that makes this a walkthrough rather than a slideshow:
 * the line lit up is the line being talked about. Everything else here — the order, the summary at
 * the end — is arrangement, and this is correctness.
 */
class CodeTourTest {

    @TempDir
    Path root;

    @BeforeEach
    void project() throws IOException {
        write(root.resolve("pom.xml"), """
                <project>
                  <artifactId>demo-parent</artifactId>
                  <modules>
                    <module>widgets</module>
                    <module>gone</module>
                  </modules>
                </project>
                """);
        write(root.resolve("widgets/pom.xml"), """
                <project>
                  <artifactId>widgets</artifactId>
                  <description>Widgets, and the gearbox that drives them.</description>
                </project>
                """);
        write(root.resolve("widgets/src/main/java/demo/Gearbox.java"), """
                package demo;

                import java.nio.file.Path;

                /** Picks a gear for a speed, and refuses a speed no gear covers. */
                public final class Gearbox {

                    /** The gear for this speed. */
                    public int gearFor(int speed) {
                        if (speed < 0) {
                            throw new IllegalArgumentException("speed cannot be negative");
                        }
                        return speed / 20;
                    }
                }
                """);
    }

    private Tour tour() {
        return CodeTour.of(root);
    }

    @Test
    @DisplayName("the line lit up is the line being talked about")
    void litLineMatchesTheNarration() {
        for (Scene scene : tour().scenes()) {
            if (scene.code().empty() || scene.code().litFrom() == 0) {
                continue;
            }
            String line = lineOf(scene);
            if (scene.said().startsWith("refuses")) {
                assertTrue(line.contains("throw"), "said a refusal over: " + line);
            }
            if (scene.said().startsWith("answers with")) {
                assertTrue(line.contains("return"), "said an answer over: " + line);
            }
            if (scene.said().startsWith("when ")) {
                assertTrue(line.contains("if"), "said a guard over: " + line);
            }
        }
    }

    @Test
    @DisplayName("a module opens with what it says about itself, then its classes")
    void modulesComeFirst() {
        List<Chapter> chapters = tour().chapters();
        assertEquals("widgets", chapters.getFirst().title());
        assertTrue(chapters.getFirst().scenes().getFirst().said()
                .contains("Widgets, and the gearbox that drives them"));
        assertEquals("Gearbox", chapters.get(1).title());
    }

    @Test
    @DisplayName("a class is introduced by its own note, not by a description of its syntax")
    void classesSpeakForThemselves() {
        Scene opening = tour().chapters().get(1).scenes().getFirst();
        assertTrue(opening.said().contains("Picks a gear for a speed"), opening.said());
        assertTrue(opening.said().contains("1 method"), opening.said());
    }

    @Test
    @DisplayName("the refusal is spoken with the reason the code gives for it")
    void refusalsCarryTheirReason() {
        assertTrue(tour().scenes().stream()
                        .anyMatch(scene -> scene.said().contains("speed cannot be negative")),
                "the message in the throw was never said");
    }

    @Test
    @DisplayName("a module listed but missing from disk is passed over rather than raised")
    void missingModulesAreSkipped() {
        assertTrue(tour().chapters().stream().noneMatch(chapter -> chapter.title().equals("gone")));
    }

    @Test
    @DisplayName("the tour ends with what it all came to")
    void summaryLast() {
        Chapter last = tour().chapters().getLast();
        assertEquals("In summary", last.title());
        assertTrue(last.scenes().getFirst().said().contains("1 class"), last.scenes().getFirst().said());
    }

    @Test
    @DisplayName("narrowing to one name tours that and nothing else")
    void narrowing() {
        assertTrue(CodeTour.of(root, "nothingcalledthis").scenes().isEmpty());
        assertFalse(CodeTour.of(root, "gearbox").scenes().isEmpty());
    }

    @Test
    @DisplayName("nothing spoken is markup, a symbol run, or empty")
    void everythingIsSayable() {
        for (Scene scene : tour().scenes()) {
            assertFalse(scene.said().isBlank(), "a scene with nothing to say: " + scene.title());
            assertFalse(scene.said().contains("{@"), "javadoc tag spoken aloud: " + scene.said());
            assertFalse(scene.said().contains("<p>"), "html spoken aloud: " + scene.said());
        }
    }

    private String lineOf(Scene scene) {
        int offset = scene.code().litFrom() - scene.code().firstLine();
        List<String> lines = scene.code().lines();
        return offset >= 0 && offset < lines.size() ? lines.get(offset) : "";
    }

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
