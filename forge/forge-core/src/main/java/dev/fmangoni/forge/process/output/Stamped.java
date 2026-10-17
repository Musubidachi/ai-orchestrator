package dev.fmangoni.forge.process.output;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Puts the time at the start of every line Forge prints.
 *
 * <p>The editor runs detached for days, and its output is now kept in a file. A file of lines saying
 * "[003] started" with no times on them answers what happened and never when — and the question
 * that sent anyone to the log in the first place was when: the editor had stopped some hours after
 * finishing a project, and nothing recorded whether that was minutes later or the next morning.
 *
 * <p>A stream wrapper rather than a logging library, per the standing rule about dependencies, and
 * rather than editing every {@code println}: there are dozens, and the next one added would not
 * have a time on it.
 */
public final class Stamped extends FilterOutputStream {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss ");

    private boolean lineStart = true;

    private Stamped(OutputStream out) {
        super(out);
    }

    /** One stamped stream, for a test to read back. */
    static Stamped around(OutputStream out) {
        return new Stamped(out);
    }

    /** Replaces standard out and error with stamped versions of themselves. */
    public static void install() {
        // Each keeps the charset of the stream it wraps. The bytes pass straight through, so
        // encoding them differently from the console underneath would mangle every non-ASCII
        // character in the output — and the output is full of em dashes.
        System.setOut(new PrintStream(new Stamped(System.out), true, System.out.charset()));
        System.setErr(new PrintStream(new Stamped(System.err), true, System.err.charset()));
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (lineStart) {
            out.write(LocalDateTime.now().format(TIME).getBytes(StandardCharsets.UTF_8));
            lineStart = false;
        }
        out.write(b);
        if (b == '\n') {
            lineStart = true;
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        for (int i = offset; i < offset + length; i++) {
            write(bytes[i]);
        }
    }
}
