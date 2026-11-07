package dev.fmangoni.forge.gate.words;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A theme changes how a screen looks, never what it says, and the gate checks that itself. */
class SameWordsTest {

    private static final String MARKET = """
            Market
            Gold  1,250
            Buy   Sell
            """;

    @TempDir
    Path repo;

    private Path words;

    @BeforeEach
    void setUp() throws IOException {
        words = Files.createDirectories(repo.resolve(SameWords.FOLDER));
    }

    private void save(String name, String text) throws IOException {
        Files.writeString(words.resolve(name), text);
    }

    @Test
    @DisplayName("the same words, laid out differently, pass")
    void sameWordsPass() throws IOException {
        save("market.default.txt", MARKET);
        save("market.halloween.txt", "Buy Sell\n\n   Market\nGold 1,250\n");

        assertEquals(Optional.empty(), SameWords.check(repo));
    }

    @Test
    @DisplayName("a changed figure fails, naming the screen, the look and both lines")
    void changedFigureFails() throws IOException {
        save("market.default.txt", MARKET);
        save("market.halloween.txt", MARKET.replace("1,250", "1,520"));

        CommandResult failure = SameWords.check(repo).orElseThrow();

        assertFalse(failure.passed());
        assertTrue(failure.command().startsWith(SameWords.FAILURE));
        assertTrue(failure.output().contains("market, halloween look:"), failure.output());
        assertTrue(failure.output().contains("- Gold 1,250"), failure.output());
        assertTrue(failure.output().contains("+ Gold 1,520"), failure.output());
    }

    @Test
    @DisplayName("a look with nothing to compare it to, or a file with no look in its name, fails")
    void misnamedFails() throws IOException {
        save("market.halloween.txt", MARKET);
        save("pets.txt", "Pets");

        String output = SameWords.check(repo).orElseThrow().output();

        assertTrue(output.contains("there is no market.default.txt"), output);
        assertTrue(output.contains("pets.txt: name it"), output);
    }

    @Test
    @DisplayName("a suite that saves no words is not checked")
    void nothingSaved() throws IOException {
        Files.delete(words);

        assertEquals(Optional.empty(), SameWords.check(repo));
    }

    @Test
    @DisplayName("words left from an earlier build are cleared, and nothing else in the folder")
    void staleWordsAreCleared() throws IOException {
        save("market.default.txt", MARKET);
        save("market.halloween.txt", MARKET.replace("Buy", "Purchase"));
        save("notes.md", "kept");

        SameWords.clear(repo);

        assertFalse(Files.exists(words.resolve("market.default.txt")));
        assertFalse(Files.exists(words.resolve("market.halloween.txt")));
        assertTrue(Files.exists(words.resolve("notes.md")));
        assertEquals(Optional.empty(), SameWords.check(repo));
    }

    @Test
    @DisplayName("a folder that was never made is nothing to clear")
    void noFolder() throws IOException {
        Files.delete(words);

        SameWords.clear(repo);

        assertFalse(Files.exists(words));
    }
}
