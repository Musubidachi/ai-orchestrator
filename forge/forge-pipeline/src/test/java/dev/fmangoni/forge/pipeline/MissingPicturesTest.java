package dev.fmangoni.forge.pipeline;

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
import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A spec naming a picture that is not there waits for its person before anything is spent. */
class MissingPicturesTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private AtomicInteger calls;

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "best"), "CODEX", List.of("codex-cheap", "codex-best")));

    private static final String PICTURE = ".forge-images/landing.png";

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), "## Approach\nBuild it.\n");
        calls = new AtomicInteger();
    }

    private Spec spec() throws IOException {
        Path file = Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: codex
                acceptance:
                  - exit 0
                evidence:
                  - artifacts/landing.png
                max_repair_rounds: 2
                max_sessions: 3
                ---
                Build the page.

                | Picture | State | Your screenshot of it |
                |---|---|---|
                | [image: %s] | The page | `artifacts/landing.png` |
                """.formatted(PICTURE));
        return SpecLoader.load(file);
    }

    private Agents agents() {
        Agent builder = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                calls.incrementAndGet();
                return new AgentResult(AgentOutcome.RATE_LIMITED, "slow down",
                        new ProcResult(1, "", "", false, Duration.ofSeconds(1)), null);
            }
        };
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CODEX, builder);
        return new Agents(map);
    }

    private RunState advance(Spec spec, RunState state) {
        return new Orchestrator(spec, paths, state, agents(), new Workspace(paths, "rules"),
                new Wishlist(List.of(spec)), TIERS, null).advance();
    }

    @Test
    @DisplayName("a missing picture holds the project before any step, naming the picture")
    void heldBeforeAnythingIsSpent() throws IOException {
        RunState fresh = RunState.builder().id("001").title("Test project")
                .assignedTo(AgentKind.CODEX).createdAt(Instant.now()).build();

        RunState state = advance(spec(), fresh);

        assertEquals(Phase.NEW, state.getPhase(), "a step ran");
        assertEquals(0, calls.get(), "an agent was asked");
        assertNotNull(state.getNeedsPerson());
        assertTrue(state.getNeedsPerson().contains(PICTURE), state.getNeedsPerson());
        assertEquals(state.getNeedsPerson(), RunStore.load(paths.stateFile()).orElseThrow().getNeedsPerson(),
                "the hold was not saved");
    }

    @Test
    @DisplayName("once the picture is there, Start lets the project go on")
    void goesOnOnceThePictureIsThere() throws IOException {
        RunState state = advance(spec(), RunState.builder().id("001").title("Test project")
                .assignedTo(AgentKind.CODEX).createdAt(Instant.now()).build());
        Path picture = paths.repo().resolve(PICTURE);
        Files.createDirectories(picture.getParent());
        Files.write(picture, new byte[] {1});

        PersonHold.release(state);
        state = advance(spec(), state);

        assertNull(state.getNeedsPerson(), state.getNeedsPerson());
        assertTrue(calls.get() > 0, "the agent was never asked");
    }
}
