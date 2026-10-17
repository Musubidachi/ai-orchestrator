package dev.fmangoni.forge.walkthrough;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;

/**
 * Turns a walkthrough into a sound file, using the voices Windows already has.
 *
 * <p>No account, no key and no network: {@code System.Speech} ships with Windows and runs offline,
 * which is the only kind of text-to-speech that belongs in a tool built on the rule that Forge
 * holds no credentials. The voice is whichever one the machine is set to — David and Zira on this
 * one.
 *
 * <p>Never throws. A walkthrough that cannot be spoken is still a walkthrough you can read: the
 * transcript is written first and separately, and a missing {@code .wav} costs nothing but the
 * listening.
 */
@UtilityClass
public class Speech {

    /** A minute of speech is about a megabyte, and a walkthrough should never run this long. */
    private final int LIMIT_MINUTES = 10;

    /**
     * Writes {@code text} to {@code wav} as spoken audio.
     *
     * <p>The text goes through a file rather than the command line: a walkthrough is full of
     * apostrophes and quotation marks, and every one of them is a way for a quoted argument to
     * arrive as something else.
     *
     * @return true when the file was written
     */
    public boolean writeWav(String text, Path wav) {
        Path script = wav.resolveSibling(wav.getFileName() + ".speech.txt");
        try {
            Files.createDirectories(wav.getParent());
            Files.writeString(script, text, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(command()).redirectErrorStream(true);
            // Through the environment rather than the command line: a Windows path is full of
            // backslashes, and every quoting layer between here and PowerShell is a chance to
            // turn one into an escape.
            builder.environment().put("FORGE_TEXT", script.toAbsolutePath().toString());
            builder.environment().put("FORGE_WAV", wav.toAbsolutePath().toString());
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(LIMIT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("speech timed out after " + LIMIT_MINUTES + " minutes: " + wav);
                return false;
            }
            return process.exitValue() == 0 && Files.isRegularFile(wav);
        } catch (IOException e) {
            System.err.println("could not write " + wav + ": " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            quietlyDelete(script);
        }
    }

    private List<String> command() {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Speech; "
                        + "$speech = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                        + "$speech.SetOutputToWaveFile([Environment]::GetEnvironmentVariable('FORGE_WAV')); "
                        + "$speech.Speak([IO.File]::ReadAllText("
                        + "[Environment]::GetEnvironmentVariable('FORGE_TEXT'), "
                        + "[Text.Encoding]::UTF8)); "
                        + "$speech.Dispose()");
    }

    private void quietlyDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A leftover text file beside the audio is untidy, not a failure worth reporting.
        }
    }
}
