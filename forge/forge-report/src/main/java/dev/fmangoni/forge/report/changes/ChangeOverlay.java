package dev.fmangoni.forge.report.changes;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** The screenshot as it is now, with the cells that changed shape washed red. */
final class ChangeOverlay {

    private static final Color WASH = new Color(230, 30, 30);

    private ChangeOverlay() {
    }

    static void write(BufferedImage now, boolean[][] changed, Path out) throws IOException {
        BufferedImage picture = new BufferedImage(now.getWidth(), now.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        try {
            g.drawImage(now, 0, 0, null);
            g.setColor(WASH);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
            int cell = ChangedCells.CELL;
            for (int row = 0; row < changed.length; row++) {
                for (int col = 0; col < changed[row].length; col++) {
                    if (changed[row][col]) {
                        g.fillRect(col * cell, row * cell, cell, cell);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        Files.createDirectories(out.getParent());
        ImageIO.write(picture, "png", out.toFile());
    }
}
