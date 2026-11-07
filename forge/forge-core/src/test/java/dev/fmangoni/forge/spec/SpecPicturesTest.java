package dev.fmangoni.forge.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.spec.model.PictureRow;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecPicturesTest {

    @TempDir
    Path root;

    private static final String TABLE = """
            ### The pictures

            | Picture | State | Your screenshot of it |
            |---|---|---|
            | [image: .forge-images/a/landing.png] | The event page | `artifacts/landing.png` |
            | [image: .forge-images/a/shop.png] | The shop, open | `artifacts/shots/shop.png` |
            """;

    private Spec spec(String body, String... evidence) throws IOException {
        StringBuilder front = new StringBuilder("""
                ---
                id: "001"
                title: Pictured
                acceptance:
                  - exit 0
                """);
        if (evidence.length > 0) {
            front.append("evidence:\n");
            for (String glob : evidence) {
                front.append("  - ").append(glob).append('\n');
            }
        }
        front.append("---\n").append(body);
        Path file = Files.writeString(root.resolve("001-pictured.md"), front.toString());
        return SpecLoader.load(file);
    }

    private Path repoWith(String... pictures) throws IOException {
        Path repo = root.resolve("repo");
        for (String picture : pictures) {
            Path file = repo.resolve(picture);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[] {1});
        }
        Files.createDirectories(repo);
        return repo;
    }

    @Test
    @DisplayName("reads each row of a pictures table, Windows line endings or not")
    void readsTheTable() {
        List<PictureRow> rows = SpecPictures.rows(TABLE.replace("\n", "\r\n"));

        assertEquals(List.of(
                new PictureRow(".forge-images/a/landing.png", "The event page", "artifacts/landing.png"),
                new PictureRow(".forge-images/a/shop.png", "The shop, open", "artifacts/shots/shop.png")),
                rows);
    }

    @Test
    @DisplayName("a spec whose pictures are there and whose screenshots are asked for is fine")
    void nothingWrong() throws IOException {
        Spec spec = spec(TABLE, "artifacts/landing.png", "artifacts/shots/*.png");
        Path repo = repoWith(".forge-images/a/landing.png", ".forge-images/a/shop.png");

        assertEquals(List.of(), SpecPictures.problems(spec, repo));
    }

    @Test
    @DisplayName("names a picture that is not in the repository")
    void missingPicture() throws IOException {
        Spec spec = spec(TABLE + "\nAlso like this: [image: .forge-images/note.png]\n",
                "artifacts/landing.png", "artifacts/shots/shop.png");
        Path repo = repoWith(".forge-images/a/landing.png", ".forge-images/note.png");

        assertEquals(List.of("the picture .forge-images/a/shop.png is not in the repository"),
                SpecPictures.problems(spec, repo));
    }

    @Test
    @DisplayName("names a screenshot the evidence list does not ask for")
    void screenshotNotInEvidence() throws IOException {
        Spec spec = spec(TABLE, "artifacts/landing.png");
        Path repo = repoWith(".forge-images/a/landing.png", ".forge-images/a/shop.png");

        List<String> problems = SpecPictures.problems(spec, repo);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("`artifacts/shots/shop.png`"), problems.get(0));
    }

    @Test
    @DisplayName("a picture outside the repository is a problem, not a file to look for")
    void outsideTheRepository() throws IOException {
        Spec spec = spec("See [image: ../elsewhere.png].\n");
        Files.write(root.resolve("elsewhere.png"), new byte[] {1});

        assertEquals(List.of("the picture ../elsewhere.png is outside the repository"),
                SpecPictures.problems(spec, repoWith()));
    }

    @Test
    @DisplayName("the syntax written in backticks is prose, not a picture")
    void backticksAreProse() throws IOException {
        Spec spec = spec("A note names a picture as `[image: path]`.\n");

        assertEquals(List.of(), SpecPictures.named(spec.body()));
        assertEquals(List.of(), SpecPictures.problems(spec, repoWith()));
    }
}
