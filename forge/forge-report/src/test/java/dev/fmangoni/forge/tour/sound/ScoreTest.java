package dev.fmangoni.forge.tour.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything the tour plays that is not speech.
 *
 * <p>Two properties matter and neither is musical. It has to be exactly as long as the narration it
 * sits under, or the picture and the sound come apart; and it has to be the same every time, or the
 * same tour produces a different file on every run and nothing can be compared.
 */
class ScoreTest {

    @TempDir
    Path out;

    private Path score(boolean music, Path wav) throws IOException {
        Score.write(List.of(4.0, 2.5, 3.0), List.of(0, 2), music, wav);
        return wav;
    }

    @Test
    @DisplayName("the score is as long as the scenes it plays under")
    void lengthFollowsTheScenes() throws IOException, UnsupportedAudioFileException {
        AudioFileFormat format =
                AudioSystem.getAudioFileFormat(score(true, out.resolve("score.wav")).toFile());
        double seconds = format.getFrameLength() / format.getFormat().getFrameRate();
        // The scenes total 9.5s; the tail is there so the last chime is not cut off.
        assertEquals(11.5, seconds, 0.1);
    }

    @Test
    @DisplayName("the same tour always gets the same music")
    void seeded() throws IOException {
        byte[] first = Files.readAllBytes(score(true, out.resolve("one.wav")));
        byte[] second = Files.readAllBytes(score(true, out.resolve("two.wav")));
        assertArrayEquals(first, second, "two runs of the same tour produced different music");
    }

    @Test
    @DisplayName("without music there is still a score, because the ticks and chimes are on it")
    void quietStillSounds() throws IOException {
        Path quiet = score(false, out.resolve("quiet.wav"));
        Path loud = score(true, out.resolve("loud.wav"));
        assertTrue(Files.size(quiet) > 0);
        assertEquals(Files.size(quiet), Files.size(loud), "the two are the same length");
        assertTrue(loudness(loud) > loudness(quiet), "turning the music off changed nothing");
    }

    /** Total energy in the file, which is all this needs to know about how much is on it. */
    private long loudness(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        long total = 0;
        for (int at = 44; at + 1 < bytes.length; at += 2) {
            total += Math.abs((short) ((bytes[at + 1] << 8) | (bytes[at] & 0xFF)));
        }
        return total;
    }
}
