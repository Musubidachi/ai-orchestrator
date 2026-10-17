package dev.fmangoni.forge.tour.show;

import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.walkthrough.Speech;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.experimental.UtilityClass;

/**
 * The tour as sound files, one per scene.
 *
 * <p>Per scene rather than one long recording, because a scene lasts exactly as long as its
 * sentence takes to say — which is what lets the video show a line for as long as it is being
 * talked about, rather than guessing at a duration and drifting out of step by the tenth one.
 *
 * <p>Recording is the slow part of this tool by a wide margin: a synthesiser runs at roughly the
 * speed of speech, so an hour of tour takes an hour to record. Scenes already recorded are left
 * alone, which makes a second run after an edit cost only what changed.
 */
@UtilityClass
public class TourAudio {

    /**
     * Records every scene into {@code directory}, returning how long each one lasts.
     *
     * <p>A scene that cannot be recorded gets its estimated length instead, so the video still has
     * a duration for it and runs slightly out of step rather than not at all.
     */
    public List<Double> write(List<Scene> scenes, Path directory) throws IOException {
        Files.createDirectories(directory);
        List<Double> seconds = new ArrayList<>();
        for (int at = 0; at < scenes.size(); at++) {
            Path wav = fileFor(directory, at);
            if (!Files.isRegularFile(wav)) {
                Speech.writeWav(scenes.get(at).said(), wav);
            }
            seconds.add(lengthOf(wav).orElseGet(scenes.get(at)::seconds));
        }
        return seconds;
    }

    /** {@code scene-0007.wav} — zero padded so the files sort the way the tour runs. */
    public Path fileFor(Path directory, int index) {
        return directory.resolve("scene-%04d.wav".formatted(index + 1));
    }

    /** How long a recording actually runs, read from the file's own header. */
    public Optional<Double> lengthOf(Path wav) {
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(wav.toFile());
            float rate = format.getFormat().getFrameRate();
            long frames = format.getFrameLength();
            if (rate <= 0 || frames <= 0) {
                return Optional.empty();
            }
            return Optional.of(frames / (double) rate);
        } catch (UnsupportedAudioFileException | IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
