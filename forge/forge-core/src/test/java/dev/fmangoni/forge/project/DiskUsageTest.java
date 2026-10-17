package dev.fmangoni.forge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk figure on each card.
 *
 * <p>It was a full walk of every run on every four-second poll: about 500 ms of a 550 ms response on
 * this machine, growing with every file a project's dependencies add.
 */
class DiskUsageTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("counts the bytes of every file under the run")
    void measuresFiles() throws Exception {
        Files.createDirectories(root.resolve("repo/src"));
        Files.writeString(root.resolve("repo/src/Main.java"), "x".repeat(700));
        Files.writeString(root.resolve("state.json"), "y".repeat(300));

        assertEquals(1000, DiskUsage.measure(root));
    }

    @Test
    @DisplayName("a poll inside a minute of the last one does not walk the disk again")
    void repeatPollsAreServedFromTheLastMeasurement() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x".repeat(100));
        long first = DiskUsage.of(root);

        Files.writeString(root.resolve("b.txt"), "y".repeat(5000));

        assertEquals(first, DiskUsage.of(root),
                "the figure was recomputed on a poll four seconds after the last");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a junction is not followed, so nothing outside the run is counted")
    void junctionsAreNotFollowed() throws Exception {
        // Files.walk goes through NTFS junctions on this JDK, and would loop on one pointing back
        // up the tree.
        Path run = Files.createDirectories(root.resolve("run/repo/node_modules"));
        Files.writeString(root.resolve("run/repo/package.json"), "x".repeat(50));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("big.bin"), "z".repeat(10_000));

        Path link = run.resolve("linked");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), elsewhere.toString())
                .redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        assertEquals(0, mklink.waitFor());

        try {
            assertEquals(50, DiskUsage.measure(root.resolve("run")));
        } finally {
            // JUnit's own temp-directory cleanup cannot cope with a junction left inside it.
            Files.delete(link);
        }
    }
}
