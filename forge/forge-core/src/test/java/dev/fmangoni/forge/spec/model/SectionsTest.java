package dev.fmangoni.forge.spec.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Splitting someone's own prose apart and putting it back.
 *
 * <p>The property that matters is not the splitting — it is that nothing is lost. A spec is the one
 * part of this system a person wrote by hand, and an editor that silently dropped a section it did
 * not recognise would be worse than one that refused to split at all. So the awkward inputs get the
 * attention here: unknown headings, missing sections, text before any heading.
 */
class SectionsTest {

    @Test
    @DisplayName("the three known headings are separated")
    void splitsTheThree() {
        Sections sections = Sections.of("""
                ## Goal
                Convert COBOL to REST.

                ## Non-goals
                No CICS.

                ## Notes
                Java 24, Maven, hand-rolled parser.
                """);

        assertEquals("Convert COBOL to REST.", sections.goal());
        assertEquals("No CICS.", sections.nonGoals());
        assertEquals("Java 24, Maven, hand-rolled parser.", sections.notes());
        assertEquals("", sections.extra());
    }

    @Test
    @DisplayName("a heading nobody planned for is kept, not dropped")
    void preservesUnknownHeadings() {
        Sections sections = Sections.of("""
                ## Goal
                Do the thing.

                ## Open questions
                Which library for PDFs?

                ## Notes
                Use Maven.
                """);

        assertTrue(sections.extra().contains("## Open questions"),
                "the heading itself has to survive or the text merges into its neighbour");
        assertTrue(sections.extra().contains("Which library for PDFs?"));
        assertEquals("Use Maven.", sections.notes());
    }

    @Test
    @DisplayName("prose before the first heading survives too")
    void preservesAPreamble() {
        Sections sections = Sections.of("""
                A note to myself before I got organised.

                ## Goal
                Do the thing.
                """);

        assertEquals("A note to myself before I got organised.", sections.extra());
        assertEquals("Do the thing.", sections.goal());
    }

    @Test
    @DisplayName("headings are matched on their text, not their exact spelling")
    void toleratesHeadingVariants() {
        Sections sections = Sections.of("""
                ### Goals
                Do it.

                ## Non-Goals
                Not that.

                # note
                Carefully.
                """);

        assertEquals("Do it.", sections.goal());
        assertEquals("Not that.", sections.nonGoals());
        assertEquals("Carefully.", sections.notes());
    }

    @Test
    @DisplayName("a missing section is empty rather than absent")
    void toleratesMissingSections() {
        Sections sections = Sections.of("## Goal\nJust this.\n");

        assertEquals("Just this.", sections.goal());
        assertEquals("", sections.nonGoals());
        assertEquals("", sections.notes());
    }

    @Test
    @DisplayName("nothing in, nothing out")
    void toleratesEmptyInput() {
        for (String input : new String[] {null, "", "   \n\n"}) {
            Sections sections = Sections.of(input);
            assertEquals("", sections.goal());
            assertEquals("", sections.extra());
        }
    }

    @Test
    @DisplayName("splitting then joining gives back an equivalent document")
    void roundTrips() {
        String body = """
                ## Goal
                Convert COBOL to REST.

                ## Non-goals
                No CICS.

                ## Notes
                Java 24, Maven.
                """;

        assertEquals(body.strip(), Sections.of(body).toBody().strip());
    }

    @Test
    @DisplayName("an unknown section is written back after the three, still intact")
    void roundTripsUnknownSections() {
        String body = """
                ## Goal
                Do the thing.

                ## Fixtures
                Six of them, listed below.
                """;

        String rebuilt = Sections.of(body).toBody();

        assertTrue(rebuilt.contains("## Goal"));
        assertTrue(rebuilt.contains("## Fixtures"));
        assertTrue(rebuilt.contains("Six of them, listed below."));
    }

    @Test
    @DisplayName("replacing one section leaves the others exactly as they were")
    void replacesOneSection() {
        Sections sections = Sections.of("""
                ## Goal
                Old goal.

                ## Non-goals
                Keep me.

                ## Notes
                Keep me too.
                """);

        Sections changed = sections.with("Goal", "  A better goal.  ");

        assertEquals("A better goal.", changed.goal());
        assertEquals("Keep me.", changed.nonGoals());
        assertEquals("Keep me too.", changed.notes());
    }

    @Test
    @DisplayName("a section name this does not know changes nothing")
    void ignoresUnknownReplacements() {
        Sections sections = Sections.of("## Goal\nUnchanged.\n");

        assertEquals(sections, sections.with("Fixtures", "something"));
        assertEquals(sections, sections.with(null, "something"));
    }
}
