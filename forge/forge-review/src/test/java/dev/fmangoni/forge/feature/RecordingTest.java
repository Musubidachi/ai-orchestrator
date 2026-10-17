package dev.fmangoni.forge.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Telling a recording from a file with the right extension.
 *
 * <p>The fakes here are the ones a turn actually produces when it is trying to satisfy a checker
 * instead of showing someone the program: a renamed text file, a single frame, a thumbnail, a file
 * of nothing. None of them is malicious — they are what "make the gate stop complaining" looks
 * like — and every one of them used to pass.
 *
 * <p>The GIFs are written with {@code ImageIO}, which is also what the house rules tell an agent to
 * use for a desktop capture. So the fixture and the real thing are produced by the same writer, and
 * a test passing here means the documented approach actually satisfies the gate.
 */
class RecordingTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------- fixtures

    /** An animated GIF of the given size, frames and per-frame delay. */
    private Path gif(String name, int width, int height, int frames, int delayHundredths)
            throws IOException {
        Path file = dir.resolve(name);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (FileImageOutputStream out = new FileImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage frame =
                        new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(i % 2 == 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.fillRect(0, 0, width, height);
                // Something that moves, so the frames are not identical after compression.
                g.setColor(Color.RED);
                g.fillRect((i * 7) % Math.max(1, width - 10), 4, 10, 10);
                g.dispose();

                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame), params);
                writer.writeToSequence(new IIOImage(frame, null, delayed(metadata,
                        delayHundredths)), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return file;
    }

    /** Sets the frame delay, which is where a GIF's duration actually lives. */
    private IIOMetadata delayed(IIOMetadata metadata, int hundredths) throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("delayTime", String.valueOf(hundredths));
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        metadata.setFromTree(format, root);
        return metadata;
    }

    private IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private Path bytes(String name, byte[] content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    /** A minimally valid MP4 header: ftyp, then moov/mvhd with a real timescale and duration. */
    private Path mp4(String name, int seconds, int width, int height) throws IOException {
        byte[] mvhd = new byte[108];
        put(mvhd, 0, mvhd.length);
        put(mvhd, 4, "mvhd");
        put(mvhd, 20, 1000);                 // timescale
        put(mvhd, 24, seconds * 1000);       // duration

        byte[] tkhd = new byte[92];
        put(tkhd, 0, tkhd.length);
        put(tkhd, 4, "tkhd");
        put(tkhd, tkhd.length - 8, width << 16);
        put(tkhd, tkhd.length - 4, height << 16);

        byte[] moov = new byte[8 + mvhd.length + tkhd.length];
        put(moov, 0, moov.length);
        put(moov, 4, "moov");
        System.arraycopy(mvhd, 0, moov, 8, mvhd.length);
        System.arraycopy(tkhd, 0, moov, 8 + mvhd.length, tkhd.length);

        byte[] ftyp = new byte[16];
        put(ftyp, 0, ftyp.length);
        put(ftyp, 4, "ftyp");
        put(ftyp, 8, "isom");

        // Padded past the stub floor, as any file with actual video in it would be.
        byte[] file = new byte[ftyp.length + moov.length + 8192];
        System.arraycopy(ftyp, 0, file, 0, ftyp.length);
        System.arraycopy(moov, 0, file, ftyp.length, moov.length);
        put(file, ftyp.length + moov.length, "mdat");
        return bytes(name, file);
    }

    /** A WebM header plus the requested number of cluster ids. */
    private Path webm(String name, int clusters) throws IOException {
        return webm(name, clusters, 8192);
    }

    private Path webm(String name, int clusters, int size) throws IOException {
        byte[] file = new byte[size];
        file[0] = 0x1A;
        file[1] = 0x45;
        file[2] = (byte) 0xDF;
        file[3] = (byte) 0xA3;
        for (int i = 0; i < clusters; i++) {
            int at = 64 + i * 512;
            file[at] = 0x1F;
            file[at + 1] = 0x43;
            file[at + 2] = (byte) 0xB6;
            file[at + 3] = 0x75;
        }
        return bytes(name, file);
    }

    private void put(byte[] target, int at, int value) {
        target[at] = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }

    private void put(byte[] target, int at, String fourCc) {
        System.arraycopy(fourCc.getBytes(StandardCharsets.US_ASCII), 0, target, at, 4);
    }

    // ------------------------------------------------------------- the fakes

    @Test
    @DisplayName("a text file with a video extension is not a recording")
    void renamedTextIsRejected() throws IOException {
        Path file = bytes("feature-x.webm",
                "totally a video, honest\n".repeat(400).getBytes(StandardCharsets.UTF_8));

        Clip clip = Recording.inspect(file);

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("not a video file"), clip.rejected());
    }

    @Test
    @DisplayName("an empty file is not a recording")
    void emptyIsRejected() throws IOException {
        assertFalse(Recording.inspect(bytes("feature-x.webm", new byte[0])).usable());
    }

    @Test
    @DisplayName("a header with nothing behind it is not a recording")
    void truncatedIsRejected() throws IOException {
        Clip clip = Recording.inspect(
                bytes("feature-x.gif", "GIF89a-and-nothing-else".getBytes(StandardCharsets.UTF_8)));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("truncated"), clip.rejected());
    }

    @Test
    @DisplayName("a container that parses but holds nothing is caught by the byte floor")
    void emptyContainerIsRejectedAsAStub() throws IOException {
        // The catch-all, and last on purpose: a file the format can explain should be explained by
        // the format. "It has one frame" is a far more useful thing to tell the next session than
        // "it is 1034 bytes", and only what no parser could account for falls through to here.
        Clip clip = Recording.inspect(webm("feature-x.webm", 1, 900));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("stub"), clip.rejected());
    }

    @Test
    @DisplayName("a single frame is a screenshot with the wrong extension")
    void oneFrameIsRejected() throws IOException {
        Clip clip = Recording.inspect(gif("feature-x.gif", 640, 480, 1, 100));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("frame"), clip.rejected());
    }

    @Test
    @DisplayName("a clip too short to show anything is rejected")
    void tooShortIsRejected() throws IOException {
        // Twenty frames at a hundredth of a second each: a fifth of a second, which is what you
        // get when something writes frames in a loop with no delay set and calls it a recording.
        Clip clip = Recording.inspect(gif("feature-x.gif", 640, 480, 20, 1));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("too short"), clip.rejected());
    }

    @Test
    @DisplayName("a thumbnail-sized canvas shows nobody anything")
    void tooSmallIsRejected() throws IOException {
        Clip clip = Recording.inspect(gif("feature-x.gif", 32, 32, 40, 10));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("too small"), clip.rejected());
    }

    @Test
    @DisplayName("a WebM container with no video data in it is rejected")
    void emptyWebmIsRejected() throws IOException {
        Clip clip = Recording.inspect(webm("feature-x.webm", 0));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("no clusters"), clip.rejected());
    }

    @Test
    @DisplayName("an MP4 with no movie header declares no duration and is rejected")
    void headerlessMp4IsRejected() throws IOException {
        byte[] file = new byte[8192];
        put(file, 0, 16);
        put(file, 4, "ftyp");
        put(file, 8, "isom");

        Clip clip = Recording.inspect(bytes("feature-x.mp4", file));

        assertFalse(clip.usable());
        assertTrue(clip.rejected().contains("moov"), clip.rejected());
    }

    // ------------------------------------------------------------- the real thing

    @Test
    @DisplayName("an animated GIF written the way AGENTS.md describes passes")
    void realGifPasses() throws IOException {
        // The house rules tell a desktop project to capture frames with Robot and write them with
        // ImageIO. If that route did not satisfy the gate, the documentation would be sending
        // every desktop project into a wall.
        Clip clip = Recording.inspect(gif("feature-x.gif", 640, 480, 45, 7));

        assertTrue(clip.usable(), clip.rejected());
        assertEquals("GIF", clip.format());
        assertEquals(45, clip.frames());
        assertEquals(640, clip.width());
        assertTrue(clip.seconds() > 3, "expected about three seconds, got " + clip.seconds());
    }

    @Test
    @DisplayName("a real MP4 header passes and reports its duration")
    void realMp4Passes() throws IOException {
        Clip clip = Recording.inspect(mp4("feature-x.mp4", 6, 1280, 720));

        assertTrue(clip.usable(), clip.rejected());
        assertEquals(6.0, clip.seconds(), 0.01);
        assertEquals(1280, clip.width());
        assertEquals(720, clip.height());
    }

    @Test
    @DisplayName("a WebM with clusters passes, because its duration cannot be read cheaply")
    void realWebmPasses() throws IOException {
        // Honest about what it does not know: a Matroska duration means walking variable-width
        // EBML elements, and the answer would only ever be used to reject a stub. Clusters are
        // enough to separate a capture from an empty container.
        Clip clip = Recording.inspect(webm("feature-x.webm", 4));

        assertTrue(clip.usable(), clip.rejected());
        assertEquals("WebM", clip.format());
    }

    @Test
    @DisplayName("what a reader is told about a clip before playing it")
    void describesItself() throws IOException {
        assertTrue(Recording.inspect(gif("feature-x.gif", 640, 480, 45, 7))
                .describe().contains("640×480"));
    }
}
