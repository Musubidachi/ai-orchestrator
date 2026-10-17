package dev.fmangoni.forge.tour.say;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a line of code sounds like.
 *
 * <p>The rule under all of this is say something or say nothing. A tour that narrates every line
 * is a tour nobody listens to twice, so a line with no honest reading has to come back empty and be
 * left out — and the lines that do get read have to be read the way a person would say them, not
 * the way the symbols are written.
 */
class LineSenseTest {

    @Test
    @DisplayName("a guard is spoken as the condition it guards against")
    void guards() {
        assertEquals("when path is missing", LineSense.of("if (path == null) {"));
        assertEquals("when text is blank", LineSense.of("if (text.isBlank()) {"));
        assertEquals("when lines is not empty", LineSense.of("if (!lines.isEmpty()) {"));
    }

    @Test
    @DisplayName("a refusal is spoken with its message, because the message is the reason")
    void refusals() {
        assertEquals("refuses, saying two specs share an id",
                LineSense.of("throw new IllegalStateException(\"two specs share an id\");"));
        assertEquals("refuses with illegal argument exception",
                LineSense.of("throw new IllegalArgumentException(id);"));
    }

    @Test
    @DisplayName("what a method answers with")
    void answers() {
        assertEquals("answers with yes", LineSense.of("return true;"));
        assertEquals("answers with nothing", LineSense.of("return null;"));
        assertEquals("answers with read string from files",
                LineSense.of("return Files.readString(file);"));
        assertEquals("and that is the end of it", LineSense.of("return;"));
    }

    @Test
    @DisplayName("loops and branches are said as the shape they are")
    void shapes() {
        assertEquals("for every scene in scenes", LineSense.of("for (Scene scene : scenes) {"));
        assertEquals("branches on phase", LineSense.of("switch (phase) {"));
        assertEquals("if that fails with io exception", LineSense.of("} catch (IOException e) {"));
    }

    @Test
    @DisplayName("work is said as where the value came from")
    void work() {
        assertEquals("takes lines from read all lines from files",
                LineSense.of("List<String> lines = Files.readAllLines(file);"));
        assertEquals("calls dispose from speech", LineSense.of("speech.dispose();"));
    }

    @Test
    @DisplayName("punctuation, plumbing and anything unreadable are left out rather than described")
    void quiet() {
        assertEquals("", LineSense.of("}"));
        assertEquals("", LineSense.of("{"));
        assertEquals("", LineSense.of("// a comment explaining the next line"));
        assertEquals("", LineSense.of("@Override"));
        assertEquals("", LineSense.of("import java.nio.file.Path;"));
        assertEquals("", LineSense.of(""));
        // No reading for this, so it says nothing rather than something wrong.
        assertEquals("", LineSense.of("x -> x.stream().flatMap(y -> y.z()).toList();"));
    }

    @Test
    @DisplayName("a choice between two values is said as the question it asks")
    void choices() {
        assertEquals("answers one of two things, depending on whether flow is empty",
                LineSense.of("return flow.isEmpty() ? work(text) : flow;"));
        assertEquals("decides one of two things, depending on whether equals is under 0",
                LineSense.of("int start = equals < 0 ? 0 : equals + 1;"));
    }

    @Test
    @DisplayName("half a statement is left to the line that finishes it")
    void fragments() {
        // Reads like an answer; is actually the first third of one.
        assertEquals("", LineSense.of("return text.isEmpty()"));
        assertEquals("", LineSense.of("        || text.startsWith(\"//\")"));
        assertEquals("", LineSense.of(".map(Object::toString)"));
    }

    @Test
    @DisplayName("nothing spoken contains the symbols a synthesiser reads out one at a time")
    void nothingUnsayable() {
        String[] lines = {
            "if (a == null && b.isEmpty()) {",
            "return Files.readString(file);",
            "for (Scene scene : scenes) {",
            "throw new IllegalStateException(\"no\");",
        };
        for (String line : lines) {
            String said = LineSense.of(line);
            assertTrue(said.chars().noneMatch(letter -> "{}();=<>!&|".indexOf(letter) >= 0),
                    "said out loud as symbols: " + said);
        }
    }
}
