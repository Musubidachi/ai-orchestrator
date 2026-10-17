package dev.fmangoni.forge.review.decision;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecisionStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a truncated notes file is read as empty — which is why it must never be truncated")
    void truncatedNotesReadAsNothing() throws IOException {
        // The failure an in-place write invites, pinned so the reason for writing atomically is
        // written down where it can fail: the loader forgives a broken file by returning nothing,
        // and the next submission then saves itself over every note that was in it.
        Path file = root.resolve("decisions.json");
        Files.writeString(file, "[{\"itemId\":\"unproven-0\",\"text\":\"the draw bra");

        assertTrue(DecisionStore.load(file).isEmpty());
    }
}
