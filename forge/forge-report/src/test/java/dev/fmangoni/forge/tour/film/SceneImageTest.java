package dev.fmangoni.forge.tour.film;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scene drawn as a frame.
 *
 * <p>Frames are drawn from whatever is in the file: lines wider than the screen, methods longer
 * than the frame, narration longer than the space for it, scenes with no code at all. None of those
 * may throw — a tour is tens of thousands of frames, and one bad line must not take the film with
 * it.
 */
class SceneImageTest {

    private Scene longMethod(int lit) {
        List<String> lines = IntStream.rangeClosed(1, 60).mapToObj(n -> "line " + n).toList();
        return new Scene("Long.run", "answers with nothing",
                new CodeView("Long.java", 1, lines, lit, lit));
    }

    @Test
    @DisplayName("a frame is the size a video expects, whatever is on it")
    void alwaysSeventyTwenty() {
        BufferedImage frame = SceneImage.render(new Scene("Long", "x".repeat(2000),
                new CodeView("Wide.java", 1, List.of("code(); ".repeat(200)), 1, 1)),
                Shot.still(2000, 1));
        assertEquals(1280, frame.getWidth());
        assertEquals(720, frame.getHeight());
    }

    @Test
    @DisplayName("a scene with nothing to show still draws")
    void spokenOnly() {
        assertEquals(1280,
                SceneImage.render(Scene.spoken("In summary", "That was 8 modules."), Shot.still(19, 0))
                        .getWidth());
    }

    @Test
    @DisplayName("the window winds down to a line far below the top of a long method")
    void longMethodsScroll() {
        assertEquals(41, SceneImage.windowStart(longMethod(50).code()));
        // A line near the top needs no winding at all.
        assertEquals(1, SceneImage.windowStart(longMethod(3).code()));
    }

    @Test
    @DisplayName("the highlight is drawn when it has faded in, and not before")
    void highlightFollowsTheShot() {
        Scene scene = longMethod(50);
        double start = SceneImage.windowStart(scene.code());
        assertFalse(marked(SceneImage.render(scene, new Shot(0, start, 5, 0, 0))),
                "the highlight was drawn before it had faded in");
        assertTrue(marked(SceneImage.render(scene, new Shot(1, start, 5, 0, 0))),
                "the line being talked about was never marked");
    }

    @Test
    @DisplayName("written as a png")
    void written(@TempDir Path out) throws IOException {
        Path file = out.resolve("scene.png");
        SceneImage.write(Scene.spoken("One", "A sentence."), file);
        assertTrue(Files.size(file) > 0);
    }

    /** True when the marker beside the lit line was drawn somewhere on the frame. */
    private boolean marked(BufferedImage frame) {
        for (int y = 0; y < frame.getHeight() - 8; y++) {
            for (int x = 0; x < 80; x++) {
                if ((frame.getRGB(x, y) & 0xFFFFFF) == 0xFBBF24) {
                    return true;
                }
            }
        }
        return false;
    }
}
