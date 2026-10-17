package dev.fmangoni.forge.spec.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.spec.SpecException;
import dev.fmangoni.forge.spec.Wishlist;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The only code that edits the user's own hand-written files.
 *
 * <p>Everything else Forge writes lives under {@code runs/} and can be deleted without loss. This
 * rewrites the wishlist, which is the one part of the system a person authored — so the property
 * worth pinning is not that the rank lands, but that nothing else moves: the prose, the comments
 * beside the acceptance commands, the ordering of the front matter.
 */
class RankWriterTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("001-thing.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an existing rank is replaced in place")
    void replacesExistingRank() throws IOException {
        Path file = write("""
                ---
                id: "001"
                title: A thing
                priority: 7
                acceptance:
                  - mvn -q test
                ---

                ## Goal
                Build it.
                """);

        RankWriter.setRank(file, 2);

        assertTrue(read(file).contains("priority: 2"));
        assertTrue(!read(file).contains("priority: 7"));
    }

    @Test
    @DisplayName("a spec with no rank gets one, just inside the fence")
    void addsMissingRank() throws IOException {
        Path file = write("""
                ---
                id: "001"
                title: A thing
                acceptance:
                  - mvn -q test
                ---

                ## Goal
                Build it.
                """);

        RankWriter.setRank(file, 3);

        String[] lines = read(file).split("\n");
        assertEquals("---", lines[0]);
        assertEquals("priority: 3", lines[1], "the rank belongs in the header, not among the commands");
    }

    @Test
    @DisplayName("comments and prose survive a renumber")
    void preservesEverythingElse() throws IOException {
        // The reason this is a line edit rather than a load-and-re-serialise: round-tripping
        // through YAML would produce a valid file and quietly delete both comments below.
        Path file = write("""
                ---
                id: "001"
                title: A thing
                priority: 9
                acceptance:
                  # this one is slow, but it is the real check
                  - mvn -q verify
                evidence:
                  - out.html   # look at this by eye
                ---

                ## Goal
                Build it, carefully.

                ## Non-goals
                Do not grow a plugin system.
                """);

        RankWriter.setRank(file, 1);

        String after = read(file);
        assertTrue(after.contains("# this one is slow, but it is the real check"));
        assertTrue(after.contains("- out.html   # look at this by eye"));
        assertTrue(after.contains("Do not grow a plugin system."));
        assertTrue(after.contains("priority: 1"));
    }

    @Test
    @DisplayName("a body line that looks like the key is not mistaken for it")
    void onlyTouchesFrontMatter() throws IOException {
        Path file = write("""
                ---
                id: "001"
                title: A thing
                priority: 4
                acceptance:
                  - mvn -q test
                ---

                ## Notes
                priority: whatever the caller passes should be echoed back.
                """);

        RankWriter.setRank(file, 6);

        String after = read(file);
        assertTrue(after.contains("priority: 6"));
        assertTrue(after.contains("priority: whatever the caller passes should be echoed back."),
                "prose that happens to start with the key must not be rewritten");
    }

    @Test
    @DisplayName("a file with no front matter is refused rather than corrupted")
    void refusesFileWithoutFrontMatter() throws IOException {
        Path file = write("# Just some notes\n\nNo front matter here.\n");

        assertThrows(SpecException.class, () -> RankWriter.setRank(file, 1));
        assertEquals("# Just some notes\n\nNo front matter here.\n", read(file),
                "a refused edit must leave the file exactly as it was");
    }

    @Test
    @DisplayName("applying a ranking rewrites only the files whose rank actually changed")
    void skipsUnchangedFiles() throws IOException {
        Path unchanged = dir.resolve("001-a.md");
        Path changing = dir.resolve("002-b.md");
        Files.writeString(unchanged, spec("001", 1), StandardCharsets.UTF_8);
        Files.writeString(changing, spec("002", 9), StandardCharsets.UTF_8);

        Wishlist wishlist = Wishlist.load(dir);
        int changed = RankWriter.apply(List.of(
                new RankAssignment(wishlist.byId("001").orElseThrow(), 1),
                new RankAssignment(wishlist.byId("002").orElseThrow(), 2)));

        assertEquals(1, changed, "only 002 needed rewriting");
        assertTrue(read(changing).contains("priority: 2"));
    }

    private static String spec(String id, int priority) {
        return """
                ---
                id: "%s"
                title: Thing %s
                priority: %d
                acceptance:
                  - echo ok
                ---

                ## Goal
                Thing.
                """.formatted(id, id, priority);
    }
}
