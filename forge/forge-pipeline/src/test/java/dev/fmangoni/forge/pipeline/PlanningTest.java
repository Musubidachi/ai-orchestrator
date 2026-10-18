package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which model does which job, and the planning turn that makes the cheap ones affordable.
 *
 * <p>The point of the split is that judgement is bought once and legwork is bought cheaply: the
 * best model the vendor has decides how the project will be built, ordinary turns follow that plan
 * on a middling model, a failing project climbs back up a rung per repair round, and the review
 * happens twice — once per claimed feature on the cheapest model there is, then once properly on
 * the best. Each of those is asserted here by the model the turn actually ran on, because a tier
 * that silently stops being applied looks exactly like one that is.
 */
class PlanningTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private final List<AgentTask> tasks = new ArrayList<>();

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "middling", "strong", "best"),
            "CODEX", List.of("codex-cheap", "codex-middling", "codex-strong", "codex-best")));

    @BeforeEach
    void setUp() {
        paths = new RunPaths(root.resolve("runs/001"));
    }

    /** Records every task, and answers from whatever the caller scripted for that turn. */
    private final class Recorder implements Agent {

        private final AgentKind kind;
        private final Function<AgentTask, AgentResult> reply;

        private Recorder(AgentKind kind, Function<AgentTask, AgentResult> reply) {
            this.kind = kind;
            this.reply = reply;
        }

        @Override
        public AgentKind kind() {
            return kind;
        }

        @Override
        public AgentResult run(AgentTask task) {
            tasks.add(task);
            AgentResult result = reply.apply(task);
            if (task.readOnly()) {
                return result;
            }
            writeOverview();
            return result;
        }

        private void writeOverview() {
            try {
                Files.writeString(paths.repo().resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
                Files.writeString(paths.repo().resolve("Pipeline.java"), """
                        /** Reads the input, transforms it, writes the result. */
                        public final class Pipeline {
                            public void run(Path in, Path out) {
                                writer.write(transformer.apply(reader.read(in)), out);
                            }
                        }
                        """);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static AgentResult says(String message) {
        return new AgentResult(AgentOutcome.COMPLETED, message,
                new ProcResult(0, message, "", false, Duration.ofSeconds(1)), null);
    }

    private static AgentResult rateLimited() {
        return new AgentResult(AgentOutcome.RATE_LIMITED, "You've hit your usage limit.",
                new ProcResult(1, "", "", false, Duration.ofSeconds(1)), Instant.now().plusSeconds(3600));
    }

    /** A handoff that claims two features, so the cheap review pass has something to check. */
    private static final String HANDOFF = """
            Done.
            {"status":"complete","summary":"built","completed":["all of it"],"remaining":[],
             "sessions_needed":0,"overview":"Pipeline.java",
             "features":[{"name":"Report export","demo":"open the report"},
                         {"name":"Monthly summary","demo":"win a title"}]}
            """;

    private Spec spec() throws IOException {
        return SpecLoader.load(Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: claude
                acceptance:
                  - exit 0
                max_repair_rounds: 3
                ---
                Build the thing.
                """));
    }

    private RunState freshState() {
        return RunState.builder()
                .id("001")
                .title("Test project")
                .assignedTo(AgentKind.CLAUDE)
                .createdAt(Instant.now())
                .build();
    }

    private RunState advance(RunState state, Agent builder, Agent reviewer) throws IOException {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(builder.kind(), builder);
        map.put(reviewer.kind(), reviewer);
        Spec spec = spec();
        return new Orchestrator(spec, paths, state, new Agents(map), new Workspace(paths, "house rules"),
                new Wishlist(List.of(spec)), TIERS, null)
                .advance();
    }

    private Agent builder(Function<AgentTask, AgentResult> reply) {
        return new Recorder(AgentKind.CLAUDE, reply);
    }

    private Agent reviewer(Function<AgentTask, AgentResult> reply) {
        return new Recorder(AgentKind.CODEX, reply);
    }

    private List<String> modelsUsed() {
        return tasks.stream().map(AgentTask::model).toList();
    }

    @Test
    @DisplayName("the plan is drafted once on the best model, and the work is done on a middling one")
    void planOnceHighThenBuildLow() throws IOException {
        RunState state = advance(freshState(),
                builder(task -> says(task.readOnly() ? "## Approach\nOne class, three steps." : HANDOFF)),
                reviewer(task -> says("{\"findings\":[]}")));

        assertEquals(Phase.DONE, state.getPhase());
        AgentTask planning = tasks.get(0);
        assertTrue(planning.readOnly(), "the planning turn must not write code");
        assertEquals("best", planning.model(), "planning is the one turn worth the top of the ladder");
        assertTrue(planning.brief().contains("Plan how to build"), planning.brief());

        AgentTask building = tasks.get(1);
        assertFalse(building.readOnly());
        assertEquals("middling", building.model(), "building follows a plan; it does not need the top rung");
        assertTrue(building.brief().contains("One class, three steps."),
                "the turn that does the work was not given the plan: " + building.brief());

        assertTrue(Files.readString(paths.planFile()).contains("One class, three steps."));
        assertTrue(Files.readString(paths.planFile()).contains("planned by CLAUDE (best)"),
                "the report should be able to say who planned it");
        assertEquals(1, state.getSessions(), "planning is a call, not a session against the budget");
    }

    @Test
    @DisplayName("the cheap model reads each claimed feature, then the best model reviews once")
    void featuresCheaplyThenOneRealReview() throws IOException {
        advance(freshState(),
                builder(task -> says(task.readOnly() ? "## Approach\nOne class." : HANDOFF)),
                reviewer(task -> says(task.brief().contains("check one claim")
                        ? "{\"verdict\":\"unproven\",\"why\":\"Race.java is a stub\"}"
                        : "{\"findings\":[]}")));

        List<AgentTask> codex = tasks.stream().filter(task -> task.model().startsWith("codex-")).toList();
        assertEquals(3, codex.size(), "two claims checked, then one review");
        assertEquals(List.of("codex-cheap", "codex-cheap", "codex-best"),
                codex.stream().map(AgentTask::model).toList());
        assertTrue(codex.get(0).brief().contains("Report export"));
        assertTrue(codex.get(1).brief().contains("Monthly summary"));

        AgentTask bigReview = codex.get(2);
        assertTrue(bigReview.brief().contains("Race.java is a stub"),
                "the expensive review was not told what the cheap pass could not find");
        assertTrue(bigReview.readOnly());
    }

    @Test
    @DisplayName("a plan already on disk is followed, not re-planned")
    void anExistingPlanIsNotBoughtTwice() throws IOException {
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), "## Approach\nWritten by hand.\n");

        advance(freshState(),
                builder(task -> says(HANDOFF)),
                reviewer(task -> says("{\"findings\":[]}")));

        assertFalse(modelsUsed().contains("best"), "a plan was already there; nothing needed the top rung");
        assertTrue(tasks.get(0).brief().contains("Written by hand."),
                "the hand-written plan was not put in front of the turn that does the work");
    }

    @Test
    @DisplayName("a planner that is out of quota suspends the project in the planning phase")
    void aRefusedPlannerSuspends() throws IOException {
        RunState state = advance(freshState(),
                builder(task -> rateLimited()),
                reviewer(task -> says("{\"findings\":[]}")));

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertEquals(Phase.PLANNING, state.getResumePhase(), "it resumes into planning, not into building");
        assertEquals(0, state.getSessions());
        assertFalse(Files.exists(paths.planFile()));
        assertEquals(1, tasks.size(), "nothing was built on top of a plan that does not exist");
    }

    @Test
    @DisplayName("a planner that answers with nothing costs one call and stops the project no further")
    void anEmptyPlanIsNotFatal() throws IOException {
        RunState state = advance(freshState(),
                builder(task -> says(task.readOnly() ? "   " : HANDOFF)),
                reviewer(task -> says("{\"findings\":[]}")));

        assertEquals(Phase.DONE, state.getPhase(), "a missing plan is a worse turn, not a broken project");
        assertFalse(Files.exists(paths.planFile()));
        assertFalse(tasks.get(1).brief().contains("## The plan"), "there is no plan to follow");
    }

    @Test
    @DisplayName("each repair round already spent moves the next turn one rung up the ladder")
    void repairsClimbTheLadder() throws IOException {
        Spec spec = SpecLoader.load(Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: claude
                acceptance:
                  - exit 1
                max_repair_rounds: 2
                max_sessions: 6
                ---
                Build the thing.
                """));
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, builder(task -> says(task.readOnly() ? "## Approach\nTry." : "no handoff")));
        map.put(AgentKind.CODEX, reviewer(task -> says("{\"findings\":[]}")));

        RunState state = new Orchestrator(spec, paths, freshState(), new Agents(map),
                new Workspace(paths, "house rules"), new Wishlist(List.of(spec)), TIERS, null)
                .advance();

        assertEquals(Phase.PARKED, state.getPhase(), "the gate never passes, so it parks as it always did");
        // Planning, then the opening turn, then one turn per repair round, each a rung higher.
        assertEquals(List.of("best", "middling", "strong", "best"), modelsUsed());
    }
}
