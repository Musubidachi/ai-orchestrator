package dev.fmangoni.forge.gate;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.experimental.UtilityClass;

/**
 * Holds a project's default look still while extras are added beside it.
 *
 * <p>An extra (a holiday theme, upgraded cars, a new animation) is built as a pack the program can
 * switch to, and its whole promise is that the look the person already has does not change. A
 * review can be talked round and an agent can mean well; a picture compared with a picture cannot.
 * So once a project has a pack slot its own acceptance suite saves the default look as
 * {@link #DEFAULT_LOOK}. The first gate that passes with it keeps a copy beside the repository as the
 * reference, and every gate after that compares the two.
 *
 * <p>A changed default fails the gate with how much changed. Replacing the look on purpose clears the
 * reference ({@link #forget}), and the next gate adopts the new one. A reference with no screenshot
 * beside it fails too: deleting the screenshot is not a way past this.
 *
 * <p>Close is the same, not exact: a clock digit, a blinking caret or anti-aliasing that differs
 * between two renders of the same page is not a restyle, and failing on it would park working code.
 */
@UtilityClass
public class LookCheck {

    /** Where the project's acceptance suite saves its default look, relative to the repository. */
    public final String DEFAULT_LOOK = "artifacts/look-default.png";

    /** A pixel counts as changed when a channel differs by more than this, out of 255. */
    final int TOLERANCE = 40;

    /** The share of pixels that may change before the look counts as changed. */
    final double ALLOWED = 0.02;

    /**
     * Compares the default look with its reference, adopting the first one seen.
     *
     * @param repo      the project's repository
     * @param reference where the reference is kept, outside the repository
     * @return a failure for the gate when the default look changed or stopped being saved
     */
    public Optional<CommandResult> check(Path repo, Path reference) {
        Path current = repo.resolve(DEFAULT_LOOK);
        boolean saved = Files.isRegularFile(current);
        boolean held = Files.isRegularFile(reference);
        if (!held) {
            if (saved) {
                adopt(current, reference);
            }
            return Optional.empty();
        }
        if (!saved) {
            return fail("no longer saved", """
                    This project's default look is held still while extras are added beside it, and \
                    the acceptance commands no longer save it as %s. Save the default look's main \
                    screen there again, from the acceptance suite, with no pack switched on.""".formatted(DEFAULT_LOOK));
        }
        try {
            BufferedImage before = ImageIO.read(reference.toFile());
            BufferedImage after = ImageIO.read(current.toFile());
            if (before == null || after == null) {
                return fail("unreadable", DEFAULT_LOOK + " is not a picture this check can read. Save it as a PNG.");
            }
            if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
                return fail("size changed", """
                        The default look was saved at %dx%d and is now %dx%d. Save %s at the size \
                        it was, with no pack switched on: the default look must not change.""".formatted(
                        before.getWidth(), before.getHeight(), after.getWidth(), after.getHeight(), DEFAULT_LOOK));
            }
            double changed = changedShare(before, after);
            if (changed > ALLOWED) {
                return fail(Math.round(changed * 100) + "% changed", """
                        %d%% of the default look's pixels differ from the reference kept when its \
                        pack slot was made (%s against the reference). An extra is added beside \
                        the default look and must leave it exactly as it was: undo whatever changed \
                        the default, and keep the new look inside its own pack.""".formatted(
                        Math.round(changed * 100), DEFAULT_LOOK));
            }
            return Optional.empty();
        } catch (IOException e) {
            return fail("unreadable", "The default look could not be read: " + e.getMessage());
        }
    }

    /** Lets the next gate adopt a new default: the look was replaced on purpose. */
    public void forget(Path reference) {
        try {
            Files.deleteIfExists(reference);
        } catch (IOException e) {
            throw new IllegalStateException("cannot clear the look reference " + reference, e);
        }
    }

    double changedShare(BufferedImage before, BufferedImage after) {
        long changed = 0;
        for (int y = 0; y < before.getHeight(); y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                if (differs(before.getRGB(x, y), after.getRGB(x, y))) {
                    changed++;
                }
            }
        }
        return (double) changed / ((long) before.getWidth() * before.getHeight());
    }

    private boolean differs(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xff) - ((b >> shift) & 0xff)) > TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private void adopt(Path current, Path reference) {
        try {
            Files.createDirectories(reference.getParent());
            Files.copy(current, reference, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("cannot keep the look reference " + reference, e);
        }
    }

    private Optional<CommandResult> fail(String what, String detail) {
        return Optional.of(new CommandResult("default look: " + what, 1, detail, false, Duration.ZERO, ""));
    }
}
