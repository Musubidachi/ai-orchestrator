package dev.fmangoni.forge.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.review.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LongFilesTest {

    @TempDir
    Path repo;

    private void write(String path, int lines) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x\n".repeat(lines));
    }

    @Test
    void aLongSourceFileGetsANoteThatNeverBlocks() throws IOException {
        write("src/main/java/Big.java", 585);
        write("src/main/java/Small.java", 120);
        write("src/app/huge.ts", 900);

        List<Finding> notes = LongFiles.notes(repo,
                List.of("src/main/java/Big.java", "src/main/java/Small.java", "src/app/huge.ts"));

        assertEquals(List.of("src/app/huge.ts", "src/main/java/Big.java"),
                notes.stream().map(Finding::getFile).toList(), "longest first, short files left alone");
        assertFalse(notes.get(0).blocking());
        assertEquals("upkeep", notes.get(0).getStakes());
        assertTrue(notes.get(1).getSummary().contains("585 lines"), notes.get(1).getSummary());
    }

    @Test
    void justAtTheLimitIsFine() throws IOException {
        write("src/Edge.java", LongFiles.LIMIT);
        assertTrue(LongFiles.notes(repo, List.of("src/Edge.java")).isEmpty());
    }

    @Test
    void testsGeneratedOutputAndDataAreNotSource() {
        assertTrue(LongFiles.isSource("src/main/java/a/Analyzer.java"));
        assertTrue(LongFiles.isSource("src/ui/screens.ts"));
        assertFalse(LongFiles.isSource("src/test/java/a/AnalyzerTest.java"));
        assertFalse(LongFiles.isSource("e2e/race.spec.ts"));
        assertFalse(LongFiles.isSource("tests/test_cameras.py"));
        assertFalse(LongFiles.isSource("src/game.test.js"));
        assertFalse(LongFiles.isSource("dist/assets/index.js"));
        assertFalse(LongFiles.isSource("node_modules/x/index.js"));
        assertFalse(LongFiles.isSource("public/vendor.min.js"));
        assertFalse(LongFiles.isSource("package-lock.json"));
        assertFalse(LongFiles.isSource("README.md"));
    }

    @Test
    void aFileThatCannotBeReadIsSkipped() throws IOException {
        Path binary = repo.resolve("src/Odd.java");
        Files.createDirectories(binary.getParent());
        Files.write(binary, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});
        assertTrue(LongFiles.notes(repo, List.of("src/Odd.java", "src/Missing.java")).isEmpty());
    }
}
