package dev.fmangoni.forge.tour.film;

import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.experimental.UtilityClass;

/**
 * One scene drawn at one instant.
 *
 * <p>Drawn rather than screenshotted: a frame is a title, some code with a line lit, and the
 * sentence being said, and drawing it needs no browser, no window and no machine that happens to be
 * unlocked. It also comes out the same everywhere, which a screenshot does not.
 *
 * <p>720p, monospaced, dark. Code on a video is read at a distance and through compression, so the
 * type is larger than it would be on a page and there is less of it on screen.
 */
@UtilityClass
public class SceneImage {

    public final int WIDTH = 1280;
    public final int HEIGHT = 720;

    private final int MARGIN = 48;
    private final int CODE_SIZE = 17;
    private final int LINE_HEIGHT = 23;
    private final int MOST_ROWS = 18;
    private final int SAID_ROWS = 4;

    private final Color BACKDROP = new Color(0x16, 0x16, 0x15);
    private final Color TEXT = new Color(0xED, 0xED, 0xEB);
    private final Color DIM = new Color(0x9A, 0x9A, 0x96);
    private final Color LIT = new Color(0x3A, 0x33, 0x12);
    private final Color EDGE = new Color(0xFB, 0xBF, 0x24);
    private final Color TRACK = new Color(0x2E, 0x2E, 0x2C);

    /** Draws the scene onto a canvas that is already the right size. */
    public void draw(Graphics2D canvas, Scene scene, Shot shot) {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        canvas.setComposite(AlphaComposite.SrcOver);
        canvas.setColor(BACKDROP);
        canvas.fillRect(0, 0, WIDTH, HEIGHT);
        if (scene.code().empty()) {
            card(canvas, scene, shot);
        } else {
            heading(canvas, scene);
            code(canvas, scene.code(), shot);
            said(canvas, scene.said(), shot.typed());
        }
        progress(canvas, shot.progress());
    }

    /** Convenience for a still: allocates its own image. */
    public BufferedImage render(Scene scene, Shot shot) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D canvas = image.createGraphics();
        draw(canvas, scene, shot);
        canvas.dispose();
        return image;
    }

    public void write(Scene scene, Path file) throws IOException {
        ImageIO.write(render(scene, Shot.still(scene.said().length(), 0)), "png", file.toFile());
    }

    /**
     * A chapter opening: the name, large and alone.
     *
     * <p>Something to arrive at. Without it a long tour is one unbroken wall of code, and the
     * moment where the subject actually changes looks exactly like the moment where it does not.
     */
    private void card(Graphics2D canvas, Scene scene, Shot shot) {
        canvas.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) shot.title()));
        canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
        canvas.setColor(TEXT);
        String title = scene.title();
        int width = canvas.getFontMetrics().stringWidth(title);
        canvas.drawString(title, (WIDTH - width) / 2, HEIGHT / 2 - 10);
        canvas.setColor(EDGE);
        canvas.fillRect((WIDTH - 60) / 2, HEIGHT / 2 + 18, 60, 3);
        canvas.setComposite(AlphaComposite.SrcOver);
        said(canvas, scene.said(), shot.typed());
    }

    private void heading(Graphics2D canvas, Scene scene) {
        canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        canvas.setColor(TEXT);
        canvas.drawString(fitted(canvas, scene.title(), WIDTH - 2 * MARGIN), MARGIN, MARGIN + 18);
        canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        canvas.setColor(DIM);
        canvas.drawString(scene.code().file(), MARGIN, MARGIN + 42);
    }

    /**
     * The code, wound so the lit line is on screen.
     *
     * <p>The window start is fractional while it moves, so a method longer than the frame scrolls
     * under the highlight rather than cutting to it. Rows are drawn at their exact offset, which
     * is what makes the movement smooth rather than a jump of one line at a time.
     */
    private void code(Graphics2D canvas, CodeView view, Shot shot) {
        canvas.setFont(new Font(Font.MONOSPACED, Font.PLAIN, CODE_SIZE));
        int top = MARGIN + 78;
        double first = shot.scroll();
        for (int row = -1; row <= MOST_ROWS; row++) {
            int number = (int) Math.floor(first) + row;
            int offset = number - view.firstLine();
            if (offset < 0 || offset >= view.lines().size()) {
                continue;
            }
            int baseline = top + (int) Math.round((number - first) * LINE_HEIGHT);
            if (baseline < top - LINE_HEIGHT || baseline > top + MOST_ROWS * LINE_HEIGHT) {
                continue;
            }
            row(canvas, view, shot, number, baseline);
        }
    }

    private void row(Graphics2D canvas, CodeView view, Shot shot, int number, int baseline) {
        boolean lit = view.lit(number);
        if (lit && shot.highlight() > 0) {
            canvas.setComposite(
                    AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) shot.highlight()));
            canvas.setColor(LIT);
            canvas.fillRect(MARGIN - 10, baseline - LINE_HEIGHT + 6, WIDTH - 2 * MARGIN + 20, LINE_HEIGHT);
            canvas.setColor(EDGE);
            canvas.fillRect(MARGIN - 10, baseline - LINE_HEIGHT + 6, 3, LINE_HEIGHT);
            canvas.setComposite(AlphaComposite.SrcOver);
        }
        canvas.setColor(DIM);
        canvas.drawString("%4d".formatted(number), MARGIN, baseline);
        canvas.setColor(lit ? TEXT : DIM);
        String text = view.lines().get(number - view.firstLine()).replace("\t", "    ");
        canvas.drawString(fitted(canvas, text, WIDTH - 2 * MARGIN - 60), MARGIN + 56, baseline);
    }

    /**
     * The sentence, appearing as it is said.
     *
     * <p>Wrapped from the whole sentence and then revealed, never wrapped from what has appeared so
     * far: otherwise every few characters the text reflows and the line you were reading moves.
     */
    private void said(Graphics2D canvas, String text, int typed) {
        canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
        canvas.setColor(TEXT);
        List<String> rows = wrapped(canvas, text, WIDTH - 2 * MARGIN);
        int top = HEIGHT - MARGIN - 18 - (rows.size() - 1) * 26;
        int shown = typed;
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            if (shown <= 0) {
                break;
            }
            canvas.drawString(line.substring(0, Math.min(line.length(), shown)), MARGIN, top + row * 26);
            shown -= line.length() + 1;
        }
    }

    /** How far through the tour, along the bottom. */
    private void progress(Graphics2D canvas, double through) {
        canvas.setColor(TRACK);
        canvas.fillRect(0, HEIGHT - 4, WIDTH, 4);
        canvas.setColor(EDGE);
        canvas.fillRect(0, HEIGHT - 4, (int) (WIDTH * Math.max(0, Math.min(1, through))), 4);
    }

    /** Wrapped to whole words, and cut off once it would run past the code above it. */
    private List<String> wrapped(Graphics2D canvas, String text, int width) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = row.isEmpty() ? word : row + " " + word;
            if (canvas.getFontMetrics().stringWidth(candidate) > width && !row.isEmpty()) {
                rows.add(row.toString());
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(candidate);
            }
            if (rows.size() == SAID_ROWS) {
                break;
            }
        }
        if (rows.size() < SAID_ROWS && !row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    /** A line cut to the width it has, with an ellipsis where it was cut. */
    private String fitted(Graphics2D canvas, String text, int width) {
        if (canvas.getFontMetrics().stringWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 4 && canvas.getFontMetrics().stringWidth(cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /**
     * The line the window starts at to keep {@code lit} on screen.
     *
     * <p>Shared with the motion, which eases from one scene's answer to the next's.
     */
    public int windowStart(CodeView view) {
        int last = view.firstLine() + view.lines().size() - 1;
        if (view.empty() || view.litFrom() < view.firstLine() || view.lines().size() <= MOST_ROWS) {
            return view.firstLine();
        }
        int wanted = view.litFrom() - MOST_ROWS / 2;
        return Math.max(view.firstLine(), Math.min(wanted, last - MOST_ROWS + 1));
    }
}
