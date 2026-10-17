package dev.fmangoni.forge.tour.sound;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import lombok.experimental.UtilityClass;

/**
 * Everything the tour plays that is not speech, as one track.
 *
 * <p>One track rather than three inputs to mix later: the music, the ticks and the chimes all know
 * where the scenes begin, so laying them out here is a few lines of arithmetic, while doing it in
 * a filter graph is a string nobody can read and a timing bug nobody can see.
 */
@UtilityClass
public class Score {

    private final float RATE = 44100;

    /**
     * Writes the bed, a tick at every scene, and a chime at every chapter.
     *
     * @param seconds how long each scene lasts, in order
     * @param chapterAt the index of the scene each chapter opens on
     */
    public void write(List<Double> seconds, List<Integer> chapterAt, boolean music, Path wav)
            throws IOException {
        double total = seconds.stream().mapToDouble(Double::doubleValue).sum();
        int frames = (int) (RATE * (total + 2));
        double[] left = new double[frames];
        double[] right = new double[frames];

        if (music) {
            Music.bed(left, right, RATE);
            // Under speech, not beside it. The narration is the point; this is the room it is in.
            quieten(left, 0.30);
            quieten(right, 0.30);
        }

        double at = 0;
        for (int scene = 0; scene < seconds.size(); scene++) {
            if (chapterAt.contains(scene)) {
                Sfx.chime(left, right, RATE, at);
            } else if (scene > 0) {
                Sfx.tick(left, right, RATE, at);
            }
            at += seconds.get(scene);
        }

        soften(left);
        soften(right);
        save(wav, left, right);
    }

    private void quieten(double[] samples, double gain) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= gain;
        }
    }

    /** Gentle saturation rather than clipping: peaks round off instead of breaking up. */
    private void soften(double[] samples) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] = Math.tanh(samples[i] * 1.5) * 0.8;
        }
    }

    private void save(Path wav, double[] left, double[] right) throws IOException {
        Files.createDirectories(wav.getParent());
        byte[] bytes = new byte[left.length * 4];
        for (int i = 0; i < left.length; i++) {
            put(bytes, i * 4, left[i]);
            put(bytes, i * 4 + 2, right[i]);
        }
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        try (AudioInputStream stream =
                new AudioInputStream(new ByteArrayInputStream(bytes), format, left.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
        }
    }

    private void put(byte[] bytes, int at, double value) {
        int sample = (int) (Math.max(-1, Math.min(1, value)) * 27000);
        bytes[at] = (byte) (sample & 0xFF);
        bytes[at + 1] = (byte) ((sample >> 8) & 0xFF);
    }
}
