package dev.fmangoni.forge.brief;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImproveBriefTest {

    private Spec spec(List<String> owns) {
        FrontMatter front = new FrontMatter();
        front.setId("008.3");
        front.setTitle("Market");
        front.setParent("008.2");
        front.setAcceptance(List.of("npm test"));
        front.setOwns(owns);
        return new Spec(Path.of("008-3-market.md"), front, "## Goal\nThe market screen.\n");
    }

    @Test
    @DisplayName("a version that owns folders is told them before it starts, and what to do when it needs another's")
    void ownedFoldersAreSaidUpFront() {
        String brief = ImproveBrief.of(spec(List.of("src/screens/market/**", "e2e/market.spec.ts")), Duration.ofMinutes(30), null);

        assertTrue(brief.contains("This version owns these folders, and may change nothing outside them:"), brief);
        assertTrue(brief.contains("- src/screens/market/**\n- e2e/market.spec.ts\n"), brief);
        assertTrue(brief.contains("say in your handoff which file and why"), brief);
    }

    @Test
    @DisplayName("a version that owns nothing is briefed as before")
    void noOwnershipNoParagraph() {
        assertFalse(ImproveBrief.of(spec(List.of()), Duration.ofMinutes(30), null).contains("owns these folders"));
    }
}
