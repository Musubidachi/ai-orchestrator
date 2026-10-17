// Copy into the project's test sources beside GifSequenceWriter.java and set the package.

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;

/**
 * Draws a command-line session as an animated GIF: the prompt and command, then the output a few
 * lines at a time, then a pause on the finished screen.
 *
 * <pre>{@code
 * ProcessResult run = ...;   // run the real tool in the test, as the acceptance command would
 * TerminalGif.write(Path.of("artifacts/feature-diff-two-patterns.gif"),
 *         "patterndiff a.txt b.txt", run.stdoutLines());
 * }</pre>
 *
 * Feed it what the tool really printed. A transcript typed into the test is a clip of a mock.
 */
public final class TerminalGif {

    private static final int WIDTH = 960;
    private static final int ROWS = 24;
    private static final int LINES_PER_FRAME = 3;
    private static final int FRAME_MILLIS = 350;
    private static final int HOLD_FRAMES = 6;

    private TerminalGif() {
    }

    public static void write(Path out, String command, List<String> output) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String> shown = new ArrayList<>();
        shown.add("$ " + command);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out.toFile());
             GifSequenceWriter gif = new GifSequenceWriter(stream, FRAME_MILLIS)) {
            gif.write(frame(shown));
            for (int i = 0; i < output.size(); i += LINES_PER_FRAME) {
                shown.addAll(output.subList(i, Math.min(output.size(), i + LINES_PER_FRAME)));
                gif.write(frame(shown));
            }
            // Long enough on the result to read it, and enough frames that even a one-line
            // output runs past the second Forge requires.
            for (int i = 0; i < HOLD_FRAMES; i++) {
                gif.write(frame(shown));
            }
        }
    }

    private static BufferedImage frame(List<String> lines) {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics metrics = probe.createGraphics().getFontMetrics(font);
        int lineHeight = metrics.getHeight();
        BufferedImage image = new BufferedImage(WIDTH, lineHeight * ROWS + 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1E1E1E));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setFont(font);
        // The newest lines, as a terminal shows them once the output runs past the window.
        List<String> visible = lines.subList(Math.max(0, lines.size() - ROWS), lines.size());
        int y = 12 + metrics.getAscent();
        for (String line : visible) {
            g.setColor(line.startsWith("$ ") ? new Color(0x7FD962) : new Color(0xE6E6E6));
            g.drawString(line, 14, y);
            y += lineHeight;
        }
        g.dispose();
        return image;
    }
}
