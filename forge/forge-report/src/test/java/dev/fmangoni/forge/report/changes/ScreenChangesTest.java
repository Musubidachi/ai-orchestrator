package dev.fmangoni.forge.report.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a screen changed since the family's last accepted version, by shape and not by colour. */
class ScreenChangesTest {

    @TempDir
    Path runs;

    private RunPaths version;

    @BeforeEach
    void setUp() throws IOException {
        Path family = runs.resolve("001");
        Files.createDirectories(family.resolve("repo"));
        version = new RunPaths(runs.resolve("001.1"), family.resolve("repo"));
        state(family, Phase.ACCEPTED);
        state(runs.resolve("001.2"), Phase.PARKED);
        state(version.root(), Phase.DONE);
    }

    private static void state(Path root, Phase phase) {
        RunState state = new RunState();
        state.setId(root.getFileName().toString());
        state.setPhase(phase);
        RunStore.save(new RunPaths(root).stateFile(), state);
    }

    /** A textured screen, so that every cell has a shape to compare. */
    private static BufferedImage screen() {
        Random random = new Random(7);
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int v = random.nextInt(256);
                image.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return image;
    }

    private static BufferedImage withBlock(BufferedImage image) {
        for (int y = 16; y < 32; y++) {
            for (int x = 16; x < 32; x++) {
                image.setRGB(x, y, 0);
            }
        }
        return image;
    }

    /** Every colour moved, every shape kept: what a theme does. */
    private static BufferedImage recoloured(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int v = image.getRGB(x, y) & 0xff;
                image.setRGB(x, y, ((v / 2 + 100) << 16) | ((v / 3 + 20) << 8) | (v / 2));
            }
        }
        return image;
    }

    private static void save(Path session, String name, BufferedImage image) throws IOException {
        Path file = session.resolve("evidence").resolve(name);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    @Test
    @DisplayName("a moved shape is washed red, a recolour and an unchanged screen are not")
    void marksOnlyShape() throws IOException {
        Path then = runs.resolve("001/evidence/session-4");
        save(then, "market.png", screen());
        save(then, "home.png", screen());
        save(then, "pets.png", screen());
        Files.createDirectories(runs.resolve("001/evidence/session-10"));
        Files.writeString(runs.resolve("001/evidence/session-10/gate.txt"), "a session that saved no pictures");
        Files.writeString(runs.resolve("001/evidence/session-11.txt"), "not a session");
        Path now = version.evidence().resolve("session-2");
        save(now, "market.png", withBlock(screen()));
        save(now, "home.png", screen());
        save(now, "pets.png", recoloured(screen()));
        save(now, "new-screen.png", screen());

        String html = ScreenChangesSection.render(version);

        assertTrue(html.contains("What changed since you accepted 001"), html);
        assertTrue(html.contains("<h3>evidence/market.png</h3>"), html);
        assertTrue(html.contains("Now, 1 part changed"), html);
        assertTrue(html.contains("src=\"../001/evidence/session-4/evidence/market.png\""), "the newest session with pictures, by number");
        assertTrue(Files.isRegularFile(version.root().resolve("changes/evidence_market.png")), "the red picture is kept");
        assertTrue(html.contains("The same as when you accepted it: evidence/home.png, evidence/pets.png."), html);
        assertFalse(html.contains("new-screen"), "nothing to compare a new screen with");
    }

    @Test
    @DisplayName("the cell that changed is the one marked")
    void theRightCell() {
        boolean[][] cells = ChangedCells.between(screen(), withBlock(screen()));

        assertEquals(1, ChangedCells.count(cells));
        assertTrue(cells[1][1]);
    }

    @Test
    @DisplayName("a family with nothing accepted yet shows nothing")
    void nothingAccepted() throws IOException {
        state(runs.resolve("001"), Phase.PARKED);
        save(version.evidence().resolve("session-1"), "home.png", screen());

        assertEquals("", ScreenChangesSection.render(version));
    }
}
