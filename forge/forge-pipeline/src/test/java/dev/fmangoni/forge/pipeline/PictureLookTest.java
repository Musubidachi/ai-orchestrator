package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelChoice;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.GateLooks;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A spec built to pictures asks its person, on its own, whether the screens came out like them. */
class PictureLookTest {

    private static final String PICTURES = """

            | Picture | State | Your screenshot of it |
            |---|---|---|
            | [image: .forge-images/title.png] | The title screen | `artifacts/title.png` |
            | [image: .forge-images/board.png] | The board | `artifacts/board.png` |
            """;

    @TempDir
    Path root;

    private RunPaths paths;
    private RunState state;

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.root());
        state = RunState.builder().id("001").title("Test project").phase(Phase.GATING).build();
    }

    private RunContext run(String body) throws IOException {
        Spec spec = SpecLoader.load(Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: codex
                acceptance:
                  - exit 0
                evidence:
                  - artifacts/title.png
                  - artifacts/board.png
                ---
                Build the screens.
                """ + body));
        ModelTiers tiers = new ModelTiers(Map.of("CODEX", List.of("cheap", "best")));
        return new RunContext(spec, paths, state, new Agents(new EnumMap<>(AgentKind.class)),
                new Workspace(paths, "rules"), new Wishlist(List.of(spec)), new ModelChoice(tiers, spec));
    }

    @Test
    @DisplayName("a spec with a pictures table asks once, and the question is saved")
    void asksOnce() throws IOException {
        PictureLook look = new PictureLook(run(PICTURES));

        look.ask();
        look.ask();

        assertEquals(List.of(PictureLook.QUESTION), state.getGateLooks());
        assertEquals(List.of(PictureLook.QUESTION),
                RunStore.load(paths.stateFile()).orElseThrow().getGateLooks(), "the question was not saved");
    }

    @Test
    @DisplayName("a spec with no pictures table, or a finished project, is not asked")
    void notAsked() throws IOException {
        new PictureLook(run("No pictures here, only [image: .forge-images/inline.png] in a sentence.\n")).ask();
        assertEquals(List.of(), state.getGateLooks());

        state.setPhase(Phase.ACCEPTED);
        new PictureLook(run(PICTURES)).ask();
        assertEquals(List.of(), state.getGateLooks());
    }

    @Test
    @DisplayName("a full gate replaces the looks, and the question is put back after the gate's own")
    void askedAgainAfterAFullGate() throws IOException {
        PictureLook look = new PictureLook(run(PICTURES));
        look.ask();

        GateLooks.record(state, List.of(new CommandResult("python mockups.py", CommandResult.LOOK,
                "LOOK: the shop's header moved\n", false, Duration.ofSeconds(4), null)));
        look.ask();

        assertEquals(2, state.getGateLooks().size(), state.getGateLooks().toString());
        assertTrue(state.getGateLooks().get(0).contains("the shop's header moved"), state.getGateLooks().toString());
        assertEquals(PictureLook.QUESTION, state.getGateLooks().get(1));
    }

    @Test
    @DisplayName("the review asks it on its own, by words the quick review knows its card by")
    void aQuestionOnItsOwn() throws IOException {
        new PictureLook(run(PICTURES)).ask();

        List<ReviewItem> items = GateLooks.with(List.of(), state);

        assertEquals(1, items.size());
        assertEquals(PictureLook.QUESTION, items.get(0).text());
        assertTrue(GateLooks.asks(items.get(0)), "it would be pre-ticked in the pile to skim");
        // ui/extras/review-pictures.js puts the pairs on the card whose headline says this.
        assertTrue(PictureLook.QUESTION.contains("the spec's pictures"));
    }

    @Test
    @DisplayName("the outline asks before any step, and again after the gate has kept its own looks")
    void placedInTheOutline() throws IOException {
        List<String> lines = Outline.of(run(PICTURES)).lines().stream().map(OutlineStep::what).toList();

        int asked = lines.indexOf("ask the person whether the screens look like the spec's pictures");
        int again = lines.indexOf("ask again about the spec's pictures, which a full gate's looks replaced");
        assertTrue(asked >= 0 && asked < lines.indexOf("set up the repository"), lines.toString());
        assertTrue(again > lines.indexOf("keep what the gate asked a person to look at"), lines.toString());
    }
}
