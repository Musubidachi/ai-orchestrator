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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole runs with stub agents and real git: while the builder says the work is unfinished, the
 * gate runs only the commands that usually finish quickly, and the full gate still stands between
 * every version and the review.
 *
 * <p>Which commands ran is read off the timings, which gain one entry per command per gate.
 */
class QuickGateRunTest {

    private static final String QUICK = "echo quick";
    private static final String SLOW = "echo slow";
    private static final int NEVER = Integer.MAX_VALUE;

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "middling", "strong", "best"),
            "CODEX", List.of("codex-cheap", "codex-middling", "codex-strong", "codex-best")));

    private static final String OVERVIEW_BODY = """
            /** Reads the input, transforms it, writes the result. */
            public final class Pipeline {
                public void run(Path in, Path out) {
                    var rows = reader.read(in);
                    var result = transformer.apply(rows);
                    writer.write(result, out);
                }
            }
            """;

    private static final AgentResult PARTIAL = said("""
            Made progress.
            {"status":"partial","summary":"built the parser",
             "completed":["lexer"],"remaining":[{"step":"the evaluator","estimate_minutes":30}],
             "sessions_needed":1,"overview":"Pipeline.java"}
            """);

    private static final AgentResult COMPLETE = said("""
            Done.
            {"status":"complete","summary":"built","completed":["all of it"],
             "remaining":[],"sessions_needed":0,"overview":"Pipeline.java"}
            """);

    @TempDir
    Path root;

    private RunPaths paths;

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), """
                ## Approach
                Build it in one class.
                """);
    }

    @Test
    @DisplayName("while the builder says unfinished the slow command waits, and the full gate runs before the review")
    void slowCommandsWaitForTheFullGate() throws IOException {
        List<String> briefs = new ArrayList<>();

        RunState state = advance(spec(6), timedBefore(), builder(3, NEVER, briefs));

        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        assertEquals(3, briefs.size());
        assertEquals(2, state.getContinuations());
        assertEquals(6, state.getCommandSeconds().get(QUICK).size(), "the quick command ran at all three gates");
        assertEquals(4, state.getCommandSeconds().get(SLOW).size(), "the slow one only at the last, full gate");
        assertEquals(List.of(), state.getNotRunAtLastGate(), "the full gate clears what the quick ones left out");
    }

    @Test
    @DisplayName("the session after a quick gate is told which commands that gate did not run")
    void theNextBriefNamesWhatDidNotRun() throws IOException {
        List<String> briefs = new ArrayList<>();

        advance(spec(6), timedBefore(), builder(3, NEVER, briefs));

        assertFalse(briefs.get(0).contains("was a quick one"), briefs.get(0));
        for (String brief : briefs.subList(1, 3)) {
            assertTrue(brief.contains("The gate after your last turn was a quick one."), brief);
            assertTrue(brief.contains("  - `" + SLOW + "`"), brief);
            assertFalse(brief.contains("  - `" + QUICK + "`"), brief);
        }
    }

    @Test
    @DisplayName("a session that built nothing gets the full gate, and goes to the review as before")
    void nothingBuiltMeansTheFullGate() throws IOException {
        List<String> briefs = new ArrayList<>();

        RunState state = advance(spec(6), timedBefore(), builder(NEVER, 2, briefs));

        assertEquals(2, briefs.size(), "the session that built nothing earns no other");
        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        assertEquals(4, state.getCommandSeconds().get(SLOW).size());
    }

    @Test
    @DisplayName("once sessions run out the gate is the full one, whatever the builder says")
    void theLastSessionGetsTheFullGate() throws IOException {
        List<String> briefs = new ArrayList<>();

        RunState state = advance(spec(3), timedBefore(), builder(NEVER, NEVER, briefs));

        assertEquals(3, briefs.size());
        assertEquals(Phase.DONE, state.getPhase(), "the commands pass, so the review decides as before");
        assertEquals(4, state.getCommandSeconds().get(SLOW).size());
    }

    // ---------------------------------------------------------------- fixtures

    private Spec spec(int maxSessions) throws IOException {
        return SpecLoader.load(Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: claude
                acceptance:
                  - %s
                  - %s
                max_repair_rounds: 2
                max_sessions: %d
                ---
                Build the thing.
                """.formatted(QUICK, SLOW, maxSessions)));
    }

    /** A project whose commands have run before: the first in a second, the second in ten minutes. */
    private static RunState timedBefore() {
        return RunState.builder()
                .id("001")
                .title("Test project")
                .assignedTo(AgentKind.CLAUDE)
                .createdAt(Instant.now())
                .commandSeconds(Map.of(QUICK, List.of(1, 1, 1), SLOW, List.of(600, 600, 600)))
                .build();
    }

    private RunState advance(Spec spec, RunState state, Agent builder) {
        Map<AgentKind, Agent> agents = new EnumMap<>(AgentKind.class);
        agents.put(AgentKind.CLAUDE, builder);
        agents.put(AgentKind.CODEX, reviewer());
        return new Orchestrator(spec, paths, state, new Agents(agents), new Workspace(paths, "house rules"),
                new Wishlist(List.of(spec)), TIERS, null)
                .advance();
    }

    /**
     * Writes a new file each turn before turn {@code idleFrom} and nothing after, and says
     * "partial" until turn {@code doneOn}, then "complete". Keeps every brief it is given.
     */
    private static Agent builder(int doneOn, int idleFrom, List<String> briefs) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                int turn = briefs.size();
                if (turn < idleFrom) {
                    write(task.cwd().resolve("step" + turn + ".java"), "x");
                    write(task.cwd().resolve("Pipeline.java"), OVERVIEW_BODY);
                    write(task.cwd().resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
                }
                return turn >= doneOn ? COMPLETE : PARTIAL;
            }
        };
    }

    private static Agent reviewer() {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                return said("{\"findings\":[]}");
            }
        };
    }

    private static AgentResult said(String message) {
        return new AgentResult(AgentOutcome.COMPLETED, message,
                new ProcResult(0, message, "", false, Duration.ofSeconds(1)), null);
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
