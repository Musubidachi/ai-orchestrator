package dev.fmangoni.forge.tour.film;

import dev.fmangoni.forge.tour.model.Scene;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Frames handed straight to the encoder, never to the disk.
 *
 * <p>A tour of a large project is tens of thousands of frames. Written out as PNGs that is
 * gigabytes of files, most of the running time spent compressing pictures nobody will look at, and
 * a directory to clean up afterwards. Written down a pipe it is none of those: the encoder reads
 * each frame as it is drawn and nothing is kept.
 *
 * <p>One image and one canvas for the whole film. Allocating a frame per frame is the kind of thing
 * that looks harmless and costs a garbage collection every few frames.
 */
final class Frames implements AutoCloseable {

    private final BufferedImage image =
            new BufferedImage(SceneImage.WIDTH, SceneImage.HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
    private final Graphics2D canvas = image.createGraphics();
    private final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    private final OutputStream encoder;

    Frames(OutputStream encoder) {
        this.encoder = encoder;
    }

    /** Draws one frame and writes it out. The byte order is already what the encoder was told. */
    void write(Scene scene, Shot shot) throws IOException {
        SceneImage.draw(canvas, scene, shot);
        encoder.write(pixels);
    }

    @Override
    public void close() throws IOException {
        canvas.dispose();
        encoder.close();
    }
}
