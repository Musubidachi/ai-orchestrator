package dev.fmangoni.forge.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The default look held still while extras are added: a reference adopted from the first gate that
 * saves one, and every gate after that compared with it.
 */
class LookCheckTest {

    @TempDir
    Path root;

    private Path repo() {
        return root.resolve("repo");
    }

    private Path reference() {
        return root.resolve("look-reference.png");
    }

    /** A 200x100 screen: a background, and a block where the cars would be. */
    private void save(Color background, Color block, int width) throws IOException {
        BufferedImage image = new BufferedImage(width, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, 100);
        g.setColor(block);
        g.fillRect(20, 20, 120, 60);
        g.dispose();
        Path file = repo().resolve(LookCheck.DEFAULT_LOOK);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    @Test
    @DisplayName("a project with no pack slot is not held to anything")
    void nothingToHoldBeforeASlot() {
        assertTrue(LookCheck.check(repo(), reference()).isEmpty());
        assertFalse(Files.exists(reference()));
    }

    @Test
    @DisplayName("the first default look saved becomes the reference, and the same look passes after it")
    void theFirstLookIsAdopted() throws IOException {
        save(Color.CYAN, Color.MAGENTA, 200);
        assertTrue(LookCheck.check(repo(), reference()).isEmpty());
        assertTrue(Files.isRegularFile(reference()), "kept beside the repository");

        save(Color.CYAN, Color.MAGENTA, 200);
        assertTrue(LookCheck.check(repo(), reference()).isEmpty());
    }

    @Test
    @DisplayName("a changed default look fails the gate and says how much changed")
    void aChangedLookFails() throws IOException {
        save(Color.CYAN, Color.MAGENTA, 200);
        LookCheck.check(repo(), reference());

        save(Color.CYAN, Color.ORANGE, 200);
        Optional<CommandResult> failed = LookCheck.check(repo(), reference());

        assertTrue(failed.isPresent());
        assertFalse(failed.get().passed());
        assertTrue(failed.get().command().startsWith("default look: 36% changed"), failed.get().command());
        assertTrue(failed.get().output().contains("must leave it exactly as it was"), failed.get().output());
    }

    @Test
    @DisplayName("a pixel or two of difference between renders is not a restyle")
    void renderNoiseIsTolerated() throws IOException {
        save(Color.CYAN, Color.MAGENTA, 200);
        LookCheck.check(repo(), reference());

        save(Color.CYAN, Color.MAGENTA, 200);
        BufferedImage noisy = ImageIO.read(repo().resolve(LookCheck.DEFAULT_LOOK).toFile());
        noisy.setRGB(5, 5, Color.BLACK.getRGB());
        noisy.setRGB(6, 5, new Color(0, 230, 240).getRGB());
        ImageIO.write(noisy, "png", repo().resolve(LookCheck.DEFAULT_LOOK).toFile());

        assertTrue(LookCheck.check(repo(), reference()).isEmpty());
    }

    @Test
    @DisplayName("a different size, or no screenshot at all, is a failure and not a way past the check")
    void sizeAndAbsenceFail() throws IOException {
        save(Color.CYAN, Color.MAGENTA, 200);
        LookCheck.check(repo(), reference());

        save(Color.CYAN, Color.MAGENTA, 180);
        assertEquals("default look: size changed", LookCheck.check(repo(), reference()).orElseThrow().command());

        Files.delete(repo().resolve(LookCheck.DEFAULT_LOOK));
        assertEquals("default look: no longer saved", LookCheck.check(repo(), reference()).orElseThrow().command());
    }

    @Test
    @DisplayName("replacing the look on purpose clears the reference, and the next look is adopted")
    void forgettingAdoptsTheNext() throws IOException {
        save(Color.CYAN, Color.MAGENTA, 200);
        LookCheck.check(repo(), reference());

        LookCheck.forget(reference());
        save(Color.PINK, Color.YELLOW, 200);

        assertTrue(LookCheck.check(repo(), reference()).isEmpty());
        save(Color.PINK, Color.YELLOW, 200);
        assertTrue(LookCheck.check(repo(), reference()).isEmpty(), "held to the new default from now on");
    }
}
