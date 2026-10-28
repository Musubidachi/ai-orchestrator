package dev.fmangoni.forge.review.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Letting a finding go has to point at something: the line that fixes it, or why it was wrong.
 *
 * <p>003.3's last recheck ruled both of its findings fixed in under three seconds, with no note.
 */
class UnexplainedRulingTest {

    @Test
    @DisplayName("fixed or withdrawn without a note is unexplained; still_present needs no defence")
    void whichRulingsNeedANote() {
        assertTrue(new Recheck("F1", "fixed", null).unexplained());
        assertTrue(new Recheck("F1", "withdrawn", "  ").unexplained());
        assertFalse(new Recheck("F1", "fixed", "src/Docx.java: cells.sort(byColumn)").unexplained());
        assertFalse(new Recheck("F1", "still_present", null).unexplained(),
                "keeping a finding costs nothing, so it is never refused for want of a note");
        assertFalse(new Recheck("F1", "probably", null).unexplained(), "not settled, so it stays anyway");
    }

    @Test
    @DisplayName("a review names the findings it let go without a word, and a first review has none")
    void aReviewNamesThem() {
        Review review = new Review();
        review.setRechecks(new ArrayList<>(List.of(
                new Recheck("F1", "fixed", ""),
                new Recheck("F2", "fixed", "Store.ts:40 now checks the balance"),
                new Recheck("F3", "withdrawn", null))));

        assertEquals(List.of("F1", "F3"), review.unexplainedRulings());
        assertEquals(List.of(), new Review().unexplainedRulings());

        Review old = new Review();
        old.setRechecks(null);
        assertEquals(List.of(), old.unexplainedRulings(), "a review file written before rechecks existed");
    }
}
