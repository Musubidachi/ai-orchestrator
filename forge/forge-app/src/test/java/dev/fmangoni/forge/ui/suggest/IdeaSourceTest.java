package dev.fmangoni.forge.ui.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor's idea generator: a program Forge built, run from its compiled classes in a folder of
 * its own. {@link FakeIdeaGenerator} stands in for 006, put where 006's classes would be.
 */
class IdeaSourceTest {

    @TempDir
    Path home;

    private Path runs() {
        return home.resolve("runs");
    }

    /** The fake generator's class, copied to where a built project's classes are. */
    private Path built() throws Exception {
        Path compiled = Path.of(FakeIdeaGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String relative = FakeIdeaGenerator.class.getName().replace('.', '/') + ".class";
        Path classes = runs().resolve("006/repo/target/classes");
        Files.createDirectories(classes.resolve(relative).getParent());
        Files.copy(compiled.resolve(relative), classes.resolve(relative));
        return runs().resolve("006/repo");
    }

    private IdeaSource configured() throws Exception {
        Files.writeString(home.resolve(IdeaSource.CONFIG), """
                {"project": "006", "mainClass": "%s", "categories": ["cli tool", "browser extension"]}
                """.formatted(FakeIdeaGenerator.class.getName()));
        return IdeaSource.load(home, runs()).orElseThrow();
    }

    @Test
    @DisplayName("with no generator named beside the wishlist there are no idea buttons")
    void noneNamedNoneOffered() throws Exception {
        assertTrue(IdeaSource.load(home, runs()).isEmpty());
        Files.writeString(home.resolve(IdeaSource.CONFIG), "{\"project\": \"006\"}");
        assertTrue(IdeaSource.load(home, runs()).isEmpty(), "a main class is needed to run anything");
    }

    @Test
    @DisplayName("each idea is a fresh one, with the filters passed through, and nothing is left in the project")
    void eachIdeaIsFresh() throws Exception {
        Path repo = built();
        IdeaSource ideas = configured();
        assertEquals(List.of("cli tool", "browser extension"), ideas.categories());

        JsonNode first = ideas.next("cli tool", "3");
        JsonNode second = ideas.next("", "");

        assertTrue(first.path("name").asText().startsWith("Idea "), first.toString());
        assertEquals("category=cli tool effort=3", first.path("description").asText());
        assertEquals("category= effort=", second.path("description").asText(), "no filter is no filter");
        assertNotEquals(first.path("name").asText(), second.path("name").asText(), "a reroll is another seed");
        try (Stream<Path> left = Files.list(repo)) {
            assertEquals(List.of(repo.resolve("target")), left.toList(), "its report files went elsewhere");
        }
    }

    @Test
    @DisplayName("a generator that is not built, or that fails, says so in a sentence")
    void failuresAreSaid() throws Exception {
        IdeaSource ideas = configured();
        IllegalStateException unbuilt = assertThrows(IllegalStateException.class, () -> ideas.next("", ""));
        assertTrue(unbuilt.getMessage().startsWith("006 has not been compiled"), unbuilt.getMessage());

        built();
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> ideas.next("broken", ""));
        assertTrue(failed.getMessage().contains("Unknown category: broken"), failed.getMessage());
        assertFalse(failed.getMessage().isBlank());
    }
}
