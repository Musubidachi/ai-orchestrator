package dev.fmangoni.forge.deploy.shelf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShelfFilesTest {

    private static final long PART = 1000;

    @TempDir
    Path root;

    @Test
    @DisplayName("a file of any size comes back byte for byte, in as many pieces as it took")
    void piecesComeBackWhole() throws Exception {
        Path runs = Files.createDirectories(root.resolve("runs"));
        Path repo = Files.createDirectories(runs.resolve("003").resolve("repo"));
        Random random = new Random(3);
        int[] sizes = {0, 1, 999, 1000, 1001, 2000, 2500};
        for (int size : sizes) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            Files.createDirectories(runs.resolve("003").resolve("logs"));
            Files.write(runs.resolve("003").resolve("logs").resolve(size + ".log"), bytes);
        }

        ShelfManifest manifest = new ShelfFiles(PART).pack(runs, "003", List.of("003"), repo, "h".repeat(40), "main",
                List.of(), List.of());
        for (ShelfEntry entry : manifest.files()) {
            long size = entry.size();
            assertEquals(size <= PART ? 1 : (size + PART - 1) / PART, entry.parts(), entry.path());
        }
        Path payload = repo.resolve(ShelfFiles.DIR);
        Path back = Files.createDirectories(root.resolve("back"));
        new ShelfFiles(PART).restore(payload, ShelfFiles.manifest(payload), "runs/", back);

        for (int size : sizes) {
            assertArrayEquals(Files.readAllBytes(runs.resolve("003").resolve("logs").resolve(size + ".log")),
                    Files.readAllBytes(back.resolve("003").resolve("logs").resolve(size + ".log")), size + " bytes");
        }
        assertFalse(Files.exists(back.resolve("003").resolve("repo")), "the repository travels as git, not as files");
    }

    @Test
    @DisplayName("a damaged piece is refused, and the good file it would have replaced is left alone")
    void aDamagedPieceIsRefused() throws Exception {
        Path runs = Files.createDirectories(root.resolve("runs"));
        Path repo = Files.createDirectories(runs.resolve("003").resolve("repo"));
        Files.writeString(runs.resolve("003").resolve("state.json"), "the state as shelved");
        new ShelfFiles(PART).pack(runs, "003", List.of("003"), repo, "h".repeat(40), "main", List.of(), List.of());
        Path payload = repo.resolve(ShelfFiles.DIR);
        Files.writeString(payload.resolve("files/runs/003/state.json"), "changed on the way");
        Path back = Files.createDirectories(root.resolve("back").resolve("003"));
        Files.writeString(back.resolve("state.json"), "the state here");

        assertThrows(IllegalStateException.class, () -> new ShelfFiles(PART).restore(payload,
                ShelfFiles.manifest(payload), "runs/", root.resolve("back")));
        assertEquals("the state here", Files.readString(back.resolve("state.json")));
    }

    @Test
    @DisplayName("a shelf that names a file outside where it is restoring is refused before anything is written")
    void aPathOutsideIsRefused() throws Exception {
        Path payload = Files.createDirectories(root.resolve("payload"));
        Files.writeString(payload.resolve(ShelfFiles.MANIFEST), """
                {"family":"003","versions":["003"],"head":"h","localBranch":"main",
                 "files":[{"path":"runs/../../outside.txt","size":1,"sha256":"x","parts":1}],"deleted":[]}
                """);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new ShelfFiles(PART)
                .restore(payload, ShelfFiles.manifest(payload), "runs/", Files.createDirectories(root.resolve("runs"))));
        assertTrue(refused.getMessage().contains("outside its place"), refused.getMessage());
        assertFalse(Files.exists(root.getParent().resolve("outside.txt")));
    }
}
