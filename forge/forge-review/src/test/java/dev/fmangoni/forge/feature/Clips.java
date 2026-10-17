package dev.fmangoni.forge.feature;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;

/**
 * Writes a recording that the gate will actually accept, for tests that are about something else.
 *
 * <p>A real animated GIF rather than a file with the right name, because the checks stopped being
 * satisfied by the latter — which is the entire point of them. Produced with {@code ImageIO}, the
 * same route AGENTS.md gives a desktop project, so a fixture that passes is evidence the documented
 * approach works.
 */
public final class Clips {

    private Clips() {
    }

    /** A few seconds of something moving, at a size a person could watch. */
    public static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (FileImageOutputStream out = new FileImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < 30; i++) {
                BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(i % 2 == 0 ? Color.DARK_GRAY : Color.GRAY);
                g.fillRect(0, 0, 640, 480);
                g.setColor(Color.RED);
                g.fillRect((i * 20) % 600, 200, 40, 40);
                g.dispose();

                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame), params);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                control(root).setAttribute("delayTime", "10");
                metadata.setFromTree(format, root);

                writer.writeToSequence(new IIOImage(frame, null, metadata), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static IIOMetadataNode control(IIOMetadataNode root) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase("GraphicControlExtension")) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode("GraphicControlExtension");
        root.appendChild(node);
        return node;
    }
}
