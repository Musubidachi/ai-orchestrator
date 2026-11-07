package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelChoice;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The outline lists every step of a run once, in order, and runs what is attached around each. */
class OutlineTest {

    @TempDir
    Path root;

    /** Forge's own outline, with nothing run: the steps are only named here. */
    private Outline forge() throws IOException {
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Spec spec = SpecLoader.load(Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: codex
                acceptance:
                  - exit 0
                ---
                Build it.
                """));
        ModelTiers tiers = new ModelTiers(Map.of("CODEX", List.of("cheap", "best")));
        return Outline.of(new RunContext(spec, paths, new RunState(), new Agents(new EnumMap<>(AgentKind.class)),
                new Workspace(paths, "rules"), new Wishlist(List.of(spec)), new ModelChoice(tiers, spec)));
    }

    @Test
    @DisplayName("every phase a run can step from has exactly one step, and the usual path is the order")
    void everyPhaseOnce() throws IOException {
        List<Phase> listed = forge().steps().stream().map(OutlineStep::phase).toList();

        List<Phase> owed = Arrays.stream(Phase.values())
                .filter(phase -> !phase.terminal() && phase != Phase.SUSPENDED).toList();
        assertEquals(owed.size(), listed.size(), listed.toString());
        assertTrue(listed.containsAll(owed), listed.toString());
        assertEquals(List.of(Phase.NEW, Phase.PLANNING, Phase.SCAFFOLDED), listed.subList(0, 3));
        assertTrue(listed.indexOf(Phase.GATING) < listed.indexOf(Phase.SECURITY_CHECK));
        assertTrue(listed.indexOf(Phase.SECURITY_CHECK) < listed.indexOf(Phase.REVIEWING));
    }

    @Test
    @DisplayName("nothing is attached to a phase with no step, where it would never run")
    void nothingUnattached() throws IOException {
        assertEquals(List.of(), forge().unattached());
    }

    @Test
    @DisplayName("the gate is surrounded by what was attached to it")
    void gateAttachments() throws IOException {
        List<String> lines = forge().lines().stream().map(OutlineStep::what).toList();

        int gate = lines.indexOf("run the spec's acceptance commands, then the gate checks");
        assertEquals("empty the folder the screens' words are saved to", lines.get(gate - 1));
        assertEquals("keep what the gate asked a person to look at", lines.get(gate + 1));
        assertEquals("hold a spec whose pictures are not there", lines.get(1));
    }

    @Test
    @DisplayName("first once, then for a step: before, the step, after; and only that step's")
    void runsInOrder() {
        List<String> done = new ArrayList<>();
        Outline outline = new Outline()
                .first("first", () -> done.add("first"))
                .before(Phase.GATING, "before", () -> done.add("before"))
                .step(Phase.GATING, "gate", () -> done.add("gate"))
                .after(Phase.GATING, "after", () -> done.add("after"))
                .step(Phase.REVIEWING, "review", () -> done.add("review"));

        outline.prepare();
        outline.run(Phase.GATING);
        outline.run(Phase.REVIEWING);

        assertEquals(List.of("first", "before", "gate", "after", "review"), done);
    }

    @Test
    @DisplayName("a phase with no step cannot be stepped from, and one step per phase")
    void noStep() {
        Outline outline = new Outline().step(Phase.GATING, "gate", () -> { });

        assertThrows(IllegalStateException.class, () -> outline.run(Phase.DONE));
        assertThrows(IllegalArgumentException.class, () -> outline.step(Phase.GATING, "again", () -> { }));
    }
}
