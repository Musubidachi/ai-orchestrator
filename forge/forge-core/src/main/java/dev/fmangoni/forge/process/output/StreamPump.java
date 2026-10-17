package dev.fmangoni.forge.process.output;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;

/**
 * Drains one process stream onto a daemon thread, teeing it to the transcript and the tail buffer.
 *
 * <p>Draining concurrently is not optional: a child process whose pipe buffer fills blocks on
 * write, so a single-threaded read of stdout followed by stderr deadlocks any agent that writes
 * much to the stream being read second.
 */
@RequiredArgsConstructor
public final class StreamPump implements Runnable {

    private static final int CHUNK = 8192;

    private final InputStream in;
    private final TailBuffer tail;
    private final ProcessLog log;
    private final String prefix;

    /** Starts draining immediately; the returned thread is joined once the process exits. */
    public static Thread start(InputStream in, TailBuffer tail, ProcessLog log, String prefix) {
        Thread t = new Thread(new StreamPump(in, tail, log, prefix), "cli-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void run() {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            copy(reader);
        } catch (IOException ignored) {
            // Closed by process teardown. Whatever reached the tail is what Forge needs.
        }
    }

    private void copy(Reader reader) throws IOException {
        char[] chunk = new char[CHUNK];
        int n;
        while ((n = reader.read(chunk)) != -1) {
            String text = new String(chunk, 0, n);
            tail.append(text);
            log.write(prefix.isEmpty() ? text : prefix + text);
        }
    }
}
