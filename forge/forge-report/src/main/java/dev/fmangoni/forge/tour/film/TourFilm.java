package dev.fmangoni.forge.tour.film;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.model.Tour;
import dev.fmangoni.forge.tour.show.TourAudio;
import dev.fmangoni.forge.tour.sound.Score;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;

/**
 * The tour as an actual video file.
 *
 * <p>Frames are drawn and piped to ffmpeg as they are made; the narration is a concat list of the
 * scene recordings; the music and the sound effects are one generated track. ffmpeg ducks the music
 * under the voice and muxes the three together. Each line therefore stays on screen for exactly as
 * long as it is being talked about, which no fixed frame rate manages over a few hundred scenes.
 *
 * <p>ffmpeg is optional and is never installed by this tool. Without it there is still a page that
 * plays itself and a folder of recordings, and the only thing missing is the single file you can
 * send someone.
 */
@UtilityClass
public class TourFilm {

    private final int FPS = 24;

    /** An encode of a long tour is minutes of work; past this something has gone wrong. */
    private final int LIMIT_MINUTES = 180;

    /** True when ffmpeg is on the path, which is the only way this tool will use one. */
    public boolean available() {
        try {
            Process probe = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Records the narration, draws the film and muxes it into {@code mp4}.
     *
     * @return the video, or empty when ffmpeg is not on the path or refused the encode
     */
    public Optional<Path> write(Tour tour, Path directory, Path mp4, boolean music)
            throws IOException {
        List<Scene> scenes = tour.scenes();
        if (scenes.isEmpty() || !available()) {
            return Optional.empty();
        }
        Files.createDirectories(directory);
        List<Double> seconds = TourAudio.write(scenes, directory);
        Path narration = narrationList(directory, scenes.size());
        Path score = directory.resolve("score.wav");
        Score.write(seconds, chapterStarts(tour), music, score);
        return encode(tour, seconds, narration, score, directory, mp4);
    }

    /** The scene each chapter opens on, which is where a chime belongs. */
    private List<Integer> chapterStarts(Tour tour) {
        List<Integer> starts = new ArrayList<>();
        int at = 0;
        for (Chapter chapter : tour.chapters()) {
            starts.add(at);
            at += chapter.scenes().size();
        }
        return starts;
    }

    private Path narrationList(Path directory, int count) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < count; at++) {
            lines.add("file '" + "scene-%04d.wav".formatted(at + 1) + "'");
        }
        Path file = directory.resolve("narration.txt");
        Files.writeString(file, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        return file;
    }

    private Optional<Path> encode(Tour tour, List<Double> seconds, Path narration, Path score,
            Path directory, Path mp4) throws IOException {
        Files.createDirectories(mp4.getParent());
        Path log = directory.resolve("ffmpeg.log");
        Process encoder = new ProcessBuilder(command(narration, score, mp4))
                .redirectOutput(log.toFile())
                .redirectError(log.toFile())
                .start();
        try (Frames frames = new Frames(encoder.getOutputStream())) {
            draw(tour, seconds, frames);
        } catch (IOException e) {
            // A pipe that closes early means ffmpeg has already given up; its log says why.
            System.err.println("the encoder stopped early, see " + log);
        }
        return finish(encoder, mp4, log);
    }

    /**
     * Every frame of the film, in order.
     *
     * <p>Frame boundaries are computed from the running total rather than per scene, so the
     * rounding of one scene's length cannot accumulate into the picture drifting away from the
     * sound over a tour of a thousand of them.
     */
    private void draw(Tour tour, List<Double> seconds, Frames frames) throws IOException {
        List<Scene> scenes = tour.scenes();
        double total = seconds.stream().mapToDouble(Double::doubleValue).sum();
        double elapsed = 0;
        double previous = 0;
        for (int at = 0; at < scenes.size(); at++) {
            Scene scene = scenes.get(at);
            double length = seconds.get(at);
            long from = Math.round(elapsed * FPS);
            long to = Math.round((elapsed + length) * FPS);
            double start = SceneImage.windowStart(scene.code());
            for (long frame = from; frame < to; frame++) {
                double into = (frame - from) / (double) FPS;
                frames.write(scene, Motion.at(
                        scene, into, length, previous, start, frame / (double) Math.max(1, total * FPS)));
            }
            previous = start;
            elapsed += length;
        }
    }

    private List<String> command(Path narration, Path score, Path mp4) {
        return List.of(
                "ffmpeg", "-y",
                "-f", "rawvideo", "-pixel_format", "bgr24",
                "-video_size", SceneImage.WIDTH + "x" + SceneImage.HEIGHT,
                "-framerate", String.valueOf(FPS), "-i", "-",
                "-f", "concat", "-safe", "0", "-i", narration.toAbsolutePath().toString(),
                "-i", score.toAbsolutePath().toString(),
                "-filter_complex", FILTER,
                "-map", "0:v", "-map", "[mixed]",
                // yuv420p is what the players people actually have will open; the defaults produce
                // a file only ffplay is happy with.
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k", "-shortest",
                mp4.toAbsolutePath().toString());
    }

    /**
     * Voice over music, with the music stepping back whenever the voice arrives.
     *
     * <p>Both are resampled and laid out as stereo first: the recordings come from a speech
     * synthesiser and the score from arithmetic, and the two agree about nothing.
     */
    private final String FILTER =
            "[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[voice];"
            + "[2:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[bed];"
            + "[voice]asplit=2[voiceone][voicetwo];"
            + "[bed][voiceone]sidechaincompress=threshold=0.03:ratio=6:attack=15:release=350[under];"
            + "[voicetwo][under]amix=inputs=2:duration=first:dropout_transition=0[mixed]";

    private Optional<Path> finish(Process encoder, Path mp4, Path log) throws IOException {
        try {
            if (!encoder.waitFor(LIMIT_MINUTES, TimeUnit.MINUTES)) {
                encoder.destroyForcibly();
                System.err.println("ffmpeg did not finish within " + LIMIT_MINUTES + " minutes");
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (encoder.exitValue() != 0) {
            System.err.println(tail(log));
            return Optional.empty();
        }
        return Files.isRegularFile(mp4) ? Optional.of(mp4) : Optional.empty();
    }

    /** ffmpeg says a great deal; what went wrong is at the end of it. */
    private String tail(Path log) throws IOException {
        if (!Files.isRegularFile(log)) {
            return "ffmpeg failed and wrote no log";
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        return String.join(System.lineSeparator(),
                lines.subList(Math.max(0, lines.size() - 12), lines.size()));
    }
}
