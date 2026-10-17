package dev.fmangoni.forge.spec.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which model does which job.
 *
 * <p>The ladders here are the shape of the real ones and not their contents: what matters is that
 * a four-rung vendor and a six-rung vendor both get answers, and that the rung is chosen by
 * position rather than by a name this code knows.
 */
class ModelChoiceTest {

    @TempDir
    Path root;

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("haiku", "sonnet", "opus", "fable"),
            "CODEX", List.of("mini", "five", "luna", "terra", "sol", "astra")));

    private Spec spec(String models) throws IOException {
        return SpecLoader.load(Files.writeString(root.resolve("001-thing.md"), """
                ---
                id: "001"
                title: Thing
                %s
                acceptance:
                  - echo ok
                ---
                Build it.
                """.formatted(models)));
    }

    private ModelChoice choice(String models) throws IOException {
        return new ModelChoice(TIERS, spec(models));
    }

    @Test
    @DisplayName("planning takes the best model, feature checks the cheapest, on either ladder")
    void endsOfTheLadder() throws IOException {
        ModelChoice choice = choice("");

        assertEquals("fable", choice.plan(AgentKind.CLAUDE));
        assertEquals("astra", choice.plan(AgentKind.CODEX));
        assertEquals("haiku", choice.featureReview(AgentKind.CLAUDE));
        assertEquals("mini", choice.featureReview(AgentKind.CODEX));
    }

    @Test
    @DisplayName("implementing starts in the middle when the spec names nothing")
    void implementingStartsMidLadder() throws IOException {
        ModelChoice choice = choice("");

        assertEquals("sonnet", choice.implement(AgentKind.CLAUDE, 0));
        assertEquals("luna", choice.implement(AgentKind.CODEX, 0));
    }

    @Test
    @DisplayName("each repair round already spent moves the next turn one rung up, and stops at the top")
    void repairsEscalate() throws IOException {
        ModelChoice choice = choice("");

        assertEquals("sonnet", choice.implement(AgentKind.CLAUDE, 0));
        assertEquals("opus", choice.implement(AgentKind.CLAUDE, 1));
        assertEquals("fable", choice.implement(AgentKind.CLAUDE, 2));
        assertEquals("fable", choice.implement(AgentKind.CLAUDE, 9), "there is nothing above the top rung");
    }

    @Test
    @DisplayName("a spec that names a model builds with it, and escalates from there")
    void specModelIsTheBaseline() throws IOException {
        ModelChoice choice = choice("claude_model: haiku\ncodex_model: terra");

        assertEquals("haiku", choice.implement(AgentKind.CLAUDE, 0));
        assertEquals("sonnet", choice.implement(AgentKind.CLAUDE, 1));
        assertEquals("sol", choice.implement(AgentKind.CODEX, 1));
    }

    @Test
    @DisplayName("a named model governs the big review; planning and feature checks keep their rungs")
    void namedModelDoesNotPinTheReadingJobs() throws IOException {
        ModelChoice choice = choice("claude_model: sonnet");

        assertEquals("sonnet", choice.bigReview(AgentKind.CLAUDE), "the spec's slider is a decision");
        assertEquals("astra", choice.bigReview(AgentKind.CODEX), "a vendor the spec says nothing about");
        assertEquals("fable", choice.plan(AgentKind.CLAUDE));
        assertEquals("haiku", choice.featureReview(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("a model the ladder does not list is used as written, not replaced by a guess")
    void unlistedModelIsRespected() throws IOException {
        ModelChoice choice = choice("claude_model: some-preview-build");

        assertEquals("some-preview-build", choice.implement(AgentKind.CLAUDE, 0));
        assertEquals("some-preview-build", choice.implement(AgentKind.CLAUDE, 3));
    }

    @Test
    @DisplayName("a vendor with no ladder leaves the CLI's own default alone")
    void emptyLadder() throws IOException {
        ModelChoice choice = new ModelChoice(new ModelTiers(Map.of()), spec(""));

        assertEquals("", choice.plan(AgentKind.CLAUDE));
        assertEquals("", choice.featureReview(AgentKind.CLAUDE));
        assertEquals("", choice.implement(AgentKind.CLAUDE, 2));
        assertEquals("", choice.bigReview(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("a ladder of one answers with it for every job")
    void singleRungLadder() throws IOException {
        ModelChoice choice = new ModelChoice(new ModelTiers(Map.of("CLAUDE", List.of("only"))), spec(""));

        assertEquals("only", choice.plan(AgentKind.CLAUDE));
        assertEquals("only", choice.implement(AgentKind.CLAUDE, 5));
        assertEquals("only", choice.featureReview(AgentKind.CLAUDE));
        assertEquals("only", choice.bigReview(AgentKind.CLAUDE));
    }
}
