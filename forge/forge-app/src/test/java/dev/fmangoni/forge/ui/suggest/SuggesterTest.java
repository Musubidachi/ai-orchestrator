package dev.fmangoni.forge.ui.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.spec.model.Sections;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drafting and refining with stub agents: the fallback, and what a sloppy reply still yields. */
class SuggesterTest {

    @TempDir
    Path root;

    private final List<AgentKind> asked = new ArrayList<>();

    private Agent replying(AgentKind kind, AgentResult result) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                asked.add(kind);
                return result;
            }
        };
    }

    private static AgentResult says(String reply) {
        return new AgentResult(AgentOutcome.COMPLETED, reply,
                new ProcResult(0, reply, "", false, Duration.ofSeconds(1)), null);
    }

    private static AgentResult limited() {
        return new AgentResult(AgentOutcome.RATE_LIMITED, "You've hit your usage limit.",
                new ProcResult(1, "", "", false, Duration.ofSeconds(1)), null);
    }

    private Suggester suggester(Agent... agents) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        for (Agent agent : agents) {
            map.put(agent.kind(), agent);
        }
        return new Suggester(new Agents(map), root.resolve(".scratch"), ModelTiers.load(root));
    }

    private static final String DRAFT = """
            Here you go:
            {"id":"007","title":"Expense tracker","stack":"node","complexity":"involved",
             "complexity_reason":"a UI and a store","acceptance":"npm test",
             "evidence":["out.html", ""],"body":"## Goal\\nTrack spending."}
            """;

    @Test
    @DisplayName("a vendor out of quota hands the idea to one that is not")
    void fallsBackOnARateLimit() {
        Suggestion suggestion = suggester(
                replying(AgentKind.CLAUDE, limited()),
                replying(AgentKind.CODEX, says(DRAFT)))
                .suggest("track my spending", AgentKind.CLAUDE, List.of("001"));

        assertNotNull(suggestion.draft(), suggestion.error());
        assertEquals("CODEX", suggestion.agent());
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX), asked);
    }

    @Test
    @DisplayName("a logged-out vendor hands the idea on too, rather than answering with its login")
    void fallsBackWhenLoggedOut() {
        AgentResult loggedOut = new AgentResult(AgentOutcome.NOT_AUTHENTICATED,
                "Not logged in · Please run /login",
                new ProcResult(1, "", "", false, Duration.ofSeconds(1)), null);

        Suggestion suggestion = suggester(
                replying(AgentKind.CLAUDE, loggedOut),
                replying(AgentKind.CODEX, says(DRAFT)))
                .suggest("track my spending", AgentKind.CLAUDE, List.of());

        assertNotNull(suggestion.draft(), suggestion.error());
        assertEquals("CODEX", suggestion.agent());
    }

    @Test
    @DisplayName("a reply that bends the format still fills the form")
    void sloppyRepliesStillFillTheForm() {
        Suggestion suggestion = suggester(replying(AgentKind.CLAUDE, says(DRAFT)))
                .suggest("track my spending", AgentKind.CLAUDE, List.of());

        assertEquals(List.of("npm test"), suggestion.draft().getFront().getAcceptance(),
                "a single command given as a string is still a command");
        assertEquals(List.of("out.html"), suggestion.draft().getFront().getEvidence(),
                "blank entries are dropped");
        assertEquals("involved", suggestion.complexity());
        assertTrue(!suggestion.draft().getFront().getAntigravityModel().isBlank(),
                "every vendor is sized to the work, the reviewer included");
        assertTrue(suggestion.draft().getBody().startsWith("## Goal"));
    }

    @Test
    @DisplayName("an ordinary failure is reported, not retried on another subscription")
    void onlyALimitFallsBack() {
        Suggestion suggestion = suggester(
                replying(AgentKind.CLAUDE, says("I would rather not.")),
                replying(AgentKind.CODEX, says(DRAFT)))
                .suggest("track my spending", AgentKind.CLAUDE, List.of());

        assertNull(suggestion.draft());
        assertTrue(suggestion.error().contains("no JSON"), suggestion.error());
        assertEquals(List.of(AgentKind.CLAUDE), asked);
    }

    @Test
    @DisplayName("refining a section nobody has heard of asks no one")
    void unknownSectionsAreRefusedUpFront() {
        Refinement refinement = suggester(replying(AgentKind.CLAUDE, says("{}")))
                .refine(AgentKind.CLAUDE, "Expense tracker",
                        new Sections("goal", "non-goals", "notes", ""), "Budget", "more detail");

        assertTrue(refinement.error().contains("unknown section"), refinement.error());
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("a refinement falls back on a limit, like a suggestion does")
    void refinementFallsBack() {
        Refinement refinement = suggester(
                replying(AgentKind.CLAUDE, limited()),
                replying(AgentKind.CODEX, says(
                        "{\"section\":\"Track every purchase.\",\"what_changed\":\"tightened\"}")))
                .refine(AgentKind.CLAUDE, "Expense tracker",
                        new Sections("goal", "non-goals", "notes", ""), "goal", "tighter");

        assertEquals("Track every purchase.", refinement.section(), refinement.error());
        assertEquals("CODEX", refinement.agent());
    }
}
