package dev.fmangoni.forge.security.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.security.model.SecurityFinding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityReviewTest {

    @Test
    @DisplayName("only a confirmed critical or high finding is serious; a hunch is a note")
    void confirmationDecidesSeriousness() {
        List<SecurityFinding> findings = SecurityReview.parse("""
                Here is my review.
                {"findings":[
                  {"severity":"high","confirmed":true,"file":"src/ui/screens.ts","title":"player name rendered as HTML",
                   "detail":"name from the URL reaches innerHTML in renderGarage","cwe":"CWE-79"},
                  {"severity":"critical","confirmed":false,"file":"src/save.ts","title":"save file might be tampered with",
                   "detail":"localStorage is trusted"},
                  {"severity":"weird","title":"odd severity"}],
                 "dismissed":[{"location":"src/ui/board.ts:1","why":"constant string"}]}
                """);

        assertEquals(3, findings.size());
        assertTrue(findings.get(0).serious());
        assertTrue(findings.get(0).evidence().startsWith("CWE-79: "), findings.get(0).evidence());
        assertFalse(findings.get(1).serious(), "unconfirmed, however severe it sounds");
        assertTrue(findings.get(1).evidence().endsWith("(not confirmed)"));
        assertEquals(SecurityFinding.MEDIUM, findings.get(2).severity(), "an unknown grade falls to the middle");
    }

    @Test
    @DisplayName("a reply with no findings list is unreadable, not clean")
    void noFindingsListIsNotClean() {
        assertNull(SecurityReview.parse("I looked and it seems fine."));
        assertNull(SecurityReview.parse("{\"summary\":\"fine\"}"));
        assertEquals(List.of(), SecurityReview.parse("{\"findings\":[]}"));
    }
}
