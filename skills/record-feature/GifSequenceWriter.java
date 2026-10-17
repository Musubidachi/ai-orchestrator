// Copy into the project's test sources and set the package. No dependency beyond the JDK.

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Writes frames to an animated GIF, looping, with one delay for every frame.
 *
 * <pre>{@code
 * try (ImageOutputStream out = ImageIO.createImageOutputStream(new File("artifacts/feature-x.gif"));
 *      GifSequenceWriter gif = new GifSequenceWriter(out, 100)) {   // 100 ms: ten frames a second
 *     for (...) gif.write(robot.createScreenCapture(windowBounds));
 * }
 * }</pre>
 *
 * Forge refuses a GIF with fewer than two frames or shorter than a second, so write at least
 * {@code 1000 / delayMillis + 1} frames.
 */
public final class GifSequenceWriter implements AutoCloseable {

    private final ImageWriter writer;
    private final ImageWriteParam params;
    private final IIOMetadata metadata;

    public GifSequenceWriter(ImageOutputStream out, int delayMillis) throws IOException {
        writer = ImageIO.getImageWritersBySuffix("gif").next();
        params = writer.getDefaultWriteParam();
        metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB), params);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(Math.max(2, delayMillis / 10)));
        control.setAttribute("transparentColorIndex", "0");

        // The NETSCAPE block is what makes it loop; without it most viewers stop on the last frame.
        IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
        loop.setAttribute("applicationID", "NETSCAPE");
        loop.setAttribute("authenticationCode", "2.0");
        loop.setUserObject(new byte[] {1, 0, 0});
        child(root, "ApplicationExtensions").appendChild(loop);

        metadata.setFromTree(format, root);
        writer.setOutput(out);
        writer.prepareWriteSequence(null);
    }

    /** Adds one frame. Any size or type; frames of different sizes are written as they are. */
    public void write(RenderedImage frame) throws IOException {
        writer.writeToSequence(new IIOImage(rgb(frame), null, metadata), params);
    }

    @Override
    public void close() throws IOException {
        writer.endWriteSequence();
        writer.dispose();
    }

    /** The GIF encoder builds its palette from RGB; a screen capture with alpha is flattened first. */
    private static RenderedImage rgb(RenderedImage frame) {
        if (frame instanceof BufferedImage image && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage flat = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        flat.createGraphics().drawRenderedImage(frame, null);
        return flat;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
