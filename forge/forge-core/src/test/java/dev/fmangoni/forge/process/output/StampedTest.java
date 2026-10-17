package dev.fmangoni.forge.process.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The time on every line of a log that is read days after it was written. */
class StampedTest {

    @Test
    @DisplayName("every line starts with the time, including one printed in several pieces")
    void everyLineIsStamped() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(Stamped.around(sink), true, StandardCharsets.UTF_8);

        out.print("[003] ");
        out.println("started");
        out.println("[003] done — DONE");

        // \R, not \n: println on Windows ends a line with \r\n, and a trailing \r fails a match.
        String[] lines = sink.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals(2, lines.length, sink.toString(StandardCharsets.UTF_8));
        for (String line : lines) {
            assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\[003\\] .*"), line);
        }
        assertTrue(lines[1].endsWith("done — DONE"), "non-ASCII must pass through intact: " + lines[1]);
    }
}
