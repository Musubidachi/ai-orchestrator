package dev.fmangoni.forge.process.output;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The on-disk transcript of one process invocation.
 *
 * <p>Appends rather than truncates: a project's repair rounds all land in the same file, so the
 * report can show the whole history of what was tried.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProcessLog implements Closeable {

    private final Writer writer;

    public static ProcessLog open(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return new ProcessLog(Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    }

    public void header(List<String> command, Path cwd) throws IOException {
        write("$ " + String.join(" ", command) + System.lineSeparator());
        write("  cwd=" + cwd + System.lineSeparator());
    }

    public void footer(int exitCode, boolean timedOut, Duration elapsed) throws IOException {
        write(System.lineSeparator()
                + "-- exit=" + exitCode + " timedOut=" + timedOut + " elapsed=" + elapsed
                + System.lineSeparator());
    }

    /** A line from Forge rather than from the command, marked so the two cannot be confused. */
    public void note(String line) throws IOException {
        write("-- forge: " + line + System.lineSeparator());
    }

    /** Synchronized because both stream pumps write here concurrently. */
    synchronized void write(String s) throws IOException {
        writer.write(s);
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
