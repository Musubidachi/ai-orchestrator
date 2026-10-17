package dev.fmangoni.forge.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Built around 001 as it stood: the fix a recheck asked about sat near the end of a file just over
 * the cap, behind a Javadoc mention and a call of the same method, and a cut from the top hid it.
 */
class FileExcerptTest {

    private static String filler(int lines) {
        return "    int padding = 0; // nothing a finding names\n".repeat(lines);
    }

    private static final String FILE = """
            /** Shared with {@link #markCallArgumentReads}. */
            final class Analyzer {
            """ + filler(400) + """
                void walk() {
                    markCallArgumentReads(c, written);
                }
            """ + filler(400) + """
                private void markCallArgumentReads(Call c, Set<String> written) {
                    // THE FIX
                }
            }
            """;

    @Test
    void aFileThatFitsIsShownWhole() {
        assertEquals(FILE, FileExcerpt.of(FILE, FILE.length(), List.of("markCallArgumentReads")));
    }

    @Test
    void aFileOverTheCapIsShownWhereItsFindingIsDeclared() {
        String shown = FileExcerpt.of(FILE, 4_000, List.of(
                "CALL analysis confuses preserving a parameter with reading it",
                "markCallArgumentReads applies needsCallerSuppliedValue, which includes parameters merely left"));

        assertTrue(shown.contains("// THE FIX"), "the declaration, not the Javadoc or the call, is what it is aimed at");
        assertTrue(shown.startsWith("… (lines 1-"), "and it says what was left out above: " + shown.substring(0, 40));
        assertTrue(shown.length() <= 4_000 + 120, "within the room it was given, give or take the markers");
    }

    @Test
    void theRarestNameWins() {
        // "written" is everywhere it appears in this file; the method name is the specific one.
        int at = FileExcerpt.anchor(FILE, List.of("the written set, and markCallArgumentReads"));
        assertEquals(FILE.lastIndexOf("markCallArgumentReads"), at);
    }

    @Test
    void withNothingToAimByItKeepsTheTopAndSaysWhereItStopped() {
        String shown = FileExcerpt.of(FILE, 4_000, List.of("the output is wrong"));
        assertTrue(shown.startsWith("/** Shared with"), shown.substring(0, 40));
        assertTrue(shown.contains("left out; the file has"), "the cut is announced");
    }

    @Test
    void anOrdinaryWordIsNotTakenForAName() {
        assertEquals(0, FileExcerpt.anchor(FILE, List.of("nothing padding")));
    }
}
