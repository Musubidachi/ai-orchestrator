package dev.fmangoni.forge.deploy.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BranchesTest {

    @Test
    @DisplayName("the initial version is main; an enhancement is its id and title, readable and legal for git")
    void names() {
        assertEquals("main", Branches.of("003", "Gamma Project"));
        assertEquals("003.2-monthly-summary", Branches.of("003.2", "Monthly Summary"));
        assertEquals("004.1-delta-project-night-theme",
                Branches.of("004.1", "Delta Project — Night theme"));
        assertEquals("003.5-cafe-creme", Branches.of("003.5", "  Café: Crème!  "), "accents and punctuation go");
        assertEquals("003.6", Branches.of("003.6", "???"), "a title with nothing usable leaves the id");
        assertEquals("gamma-project", Branches.repositoryName("Gamma Project"));
        assertEquals("003", Branches.familyOf("003.2.1"));
    }

    @Test
    @DisplayName("a long title is cut at sixty characters, never leaving a hyphen on the end")
    void longTitles() {
        String branch = Branches.of("003.7", "a very long title " + "word ".repeat(30));
        String slug = branch.substring("003.7-".length());

        assertTrue(slug.startsWith("a-very-long-title-word-word"), branch);
        assertTrue(slug.length() <= 60, slug.length() + ": " + slug);
        assertFalse(branch.endsWith("-"), branch);
    }
}
