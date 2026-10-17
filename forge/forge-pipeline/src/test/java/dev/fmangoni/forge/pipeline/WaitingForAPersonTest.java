package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ThrottledAgent;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.Signals;
import dev.fmangoni.forge.jobs.work.Eligibility;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The failures that waiting cannot fix, and the stop that must fall between two steps.
 *
 * <p>Each of these was a loop on this machine before it was a test: a broken sandbox relaunched as
 * a full agent turn the moment the last one ended, a model the account had no credits for asked
 * seven times, a gate throwing on a handoff field every twelve minutes for three days. None of them
 * counted against any budget, so none of them ever stopped.
 */
class WaitingForAPersonTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private QuotaLedger ledger;

    /** An agent with a scripted side effect and outcome, counting how often it is asked. */
    private record Scripted(AgentKind kind, AtomicInteger calls, Consumer<Path> effect,
                            AgentResult result) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            calls.incrementAndGet();
            effect.accept(task.cwd());
            return result;
        }
    }

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "best"), "CODEX", List.of("codex-cheap", "codex-best")));

    /** Verbatim shape from run 004: the CLI's logger refusing, hundreds of times, on stderr. */
    private static final String SANDBOX_REFUSING = ("ERROR codex_core::tools::router: "
            + "error=exec_command failed: CreateProcess { message: \"Rejected(\\\"Failed to create "
            + "unified exec process: helper_unknown_error: apply deny-read ACLs\\\")\" }\n").repeat(5);

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), "## Approach\nBuild it.\n");
        ledger = new QuotaLedger(root.resolve("quota.json"));
    }

    private Spec spec(int maxSessions) throws IOException {
        Path file = Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: codex
                acceptance:
                  - exit 0
                max_repair_rounds: 2
                max_sessions: %d
                ---
                Build the thing.
                """.formatted(maxSessions));
        return SpecLoader.load(file);
    }

    private RunState fresh() {
        return RunState.builder().id("001").title("Test project").assignedTo(AgentKind.CODEX)
                .createdAt(Instant.now()).build();
    }

    private Agents agents(Agent builder) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CODEX, ThrottledAgent.wrapping(builder, 1, ledger));
        map.put(AgentKind.CLAUDE, new Scripted(AgentKind.CLAUDE, new AtomicInteger(), repo -> { },
                new AgentResult(AgentOutcome.COMPLETED, "{\"findings\":[]}",
                        new ProcResult(0, "", "", false, Duration.ofSeconds(1)), null)));
        return new Agents(map);
    }

    private RunState advance(Spec spec, RunState state, Agents agents, BooleanSupplier stop) {
        return new Orchestrator(spec, paths, state, agents, new Workspace(paths, "rules"),
                new Wishlist(List.of(spec)), TIERS, null, stop).advance();
    }

    private RunState advance(Spec spec, RunState state, Agents agents) {
        return advance(spec, state, agents, () -> false);
    }

    private Eligibility eligibility(Agents agents) {
        return new Eligibility(root.resolve("runs"), agents, ledger);
    }

    private static Consumer<Path> writes(String name, String content) {
        return repo -> {
            try {
                Files.writeString(repo.resolve(name), content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    // ------------------------------------------------------------ refusals no wait fixes

    @Test
    @DisplayName("a sandbox that starts nothing waits for a person instead of relaunching the turn")
    void aBrokenToolWaitsForAPerson() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        Agents agents = agents(new Scripted(AgentKind.CODEX, calls, repo -> { },
                Signals.resultOf(new ProcResult(0, "I could not touch the workspace.",
                        SANDBOX_REFUSING, false, Duration.ofMinutes(20)), "I could not touch it.")));

        RunState state = advance(spec(12), fresh(), agents);

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertNotNull(state.getNeedsPerson(), "nothing says a person is needed");
        assertTrue(state.getLastError().startsWith("Waiting for you"), state.getLastError());
        // The loop this replaces: suspended with no reset time read as "ready now", and the queue
        // refilled the slot with the same twenty-minute turn.
        assertFalse(eligibility(agents).ready(state), "the queue would launch it again at once");
        assertTrue(ledger.isBlocked(AgentKind.CODEX), "other projects would walk into the same wall");
        assertEquals(1, calls.get());
        assertEquals(0, state.getSessions(), "a turn that could touch nothing is not work");
    }

    @Test
    @DisplayName("a logged-out CLI waits for someone to log it in, rather than being asked again")
    void aLoggedOutCliWaitsForAPerson() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        Agents agents = agents(new Scripted(AgentKind.CODEX, calls, repo -> { },
                Signals.resultOf(new ProcResult(1, "", "Not logged in. Please run codex login",
                        false, Duration.ofSeconds(2)), "")));

        RunState state = advance(spec(12), fresh(), agents);

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertTrue(state.getNeedsPerson().contains("not logged in"), state.getNeedsPerson());
        assertFalse(eligibility(agents).ready(state));
    }

    @Test
    @DisplayName("a quota refusal that names no reset still waits, instead of reading as ready now")
    void anUnstatedResetStillWaits() throws IOException {
        Agents agents = agents(new Scripted(AgentKind.CODEX, new AtomicInteger(), repo -> { },
                new AgentResult(AgentOutcome.RATE_LIMITED, "slow down",
                        new ProcResult(1, "", "", false, Duration.ofSeconds(1)), null)));

        RunState state = advance(spec(12), fresh(), agents);

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertNull(state.getNeedsPerson(), "a quota window is time's to fix, not a person's");
        assertNotNull(state.getRetryAfter());
        assertTrue(state.getRetryAfter().isAfter(Instant.now().plus(Duration.ofMinutes(10))));
        assertFalse(eligibility(agents).ready(state));
    }

    @Test
    @DisplayName("a project that has resumed stops saying it is out of quota, while its turn is running")
    void aResumedProjectDropsTheReasonItWasSuspended() throws IOException {
        // Project 004: resumed at 4:04 PM and working, with "You've hit your usage limit … try
        // again at 3:57 PM" still on its card until the turn ended half an hour later.
        Spec spec = spec(12);
        RunState state = advance(spec, fresh(), agents(new Scripted(AgentKind.CODEX,
                new AtomicInteger(), repo -> { },
                new AgentResult(AgentOutcome.RATE_LIMITED, "You've hit your usage limit.",
                        new ProcResult(1, "", "", false, Duration.ofSeconds(1)),
                        Instant.now().minusSeconds(60)))));
        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertTrue(state.getLastError().contains("usage limit"), state.getLastError());
        ledger.clear(AgentKind.CODEX);

        // What the dashboard would read off disk in the middle of the resumed turn.
        List<String> shownDuringTheTurn = new ArrayList<>();
        advance(spec, state, agents(new Scripted(AgentKind.CODEX, new AtomicInteger(),
                repo -> shownDuringTheTurn.add(String.valueOf(
                        RunStore.load(paths.stateFile()).orElseThrow().getLastError())),
                Signals.resultOf(new ProcResult(0, "", "", false, Duration.ofMinutes(5)), "done"))));

        // The resumed turn itself. Later turns in the same run say what the gate found, as they should.
        assertEquals("null", shownDuringTheTurn.getFirst(), "a working project still said it was refused");
    }

    // ------------------------------------------------------------ the same failure, again

    @Test
    @DisplayName("a CLI that fails without writing anything goes to a person on the third time")
    void repeatedFailedCallsGoToAPerson() throws IOException {
        // Project 005: seven refusals from a model the account had no credits for, ten minutes
        // apart, until someone happened to look. The javadoc said the session budget bounded
        // this; nothing on this path ever spent a session.
        AtomicInteger calls = new AtomicInteger();
        Agents agents = agents(new Scripted(AgentKind.CODEX, calls, repo -> { },
                Signals.resultOf(new ProcResult(1, "", "requires usage credits",
                        false, Duration.ofSeconds(3)), "requires usage credits")));
        Spec spec = spec(12);

        RunState state = fresh();
        for (int attempt = 1; attempt <= PersonHold.PATIENCE; attempt++) {
            state = advance(spec, state, agents);
            assertEquals(Phase.SUSPENDED, state.getPhase());
            assertEquals(attempt == PersonHold.PATIENCE, state.getNeedsPerson() != null,
                    "held after attempt " + attempt + "?");
            state.setRetryAfter(Instant.now().minusSeconds(1)); // the ten minutes pass
        }

        assertEquals(PersonHold.PATIENCE, calls.get());
        assertTrue(state.getNeedsPerson().contains("requires usage credits"),
                "the card should say what the CLI said: " + state.getNeedsPerson());
        assertFalse(eligibility(agents).ready(state));
        assertEquals(0, state.getSessions());
    }

    @Test
    @DisplayName("a turn that does work in between starts the count again")
    void aRealTurnResetsTheCount() throws IOException {
        RunState state = fresh();
        state.setFailedCallsInARow(PersonHold.PATIENCE - 1);
        Agents agents = agents(new Scripted(AgentKind.CODEX, new AtomicInteger(),
                writes("Main.java", "class Main {}"),
                Signals.resultOf(new ProcResult(0, "", "", false, Duration.ofMinutes(5)), """
                        {"status":"partial","summary":"s","completed":[],"remaining":[],
                         "sessions_needed":1,"overview":"Main.java"}
                        """)));

        state = advance(spec(1), state, agents);

        assertEquals(0, state.getFailedCallsInARow());
    }

    @Test
    @DisplayName("an explicit Start releases the hold and every count behind it")
    void releaseClearsEverything() {
        RunState state = fresh();
        state.setErrorsInARow(2);
        state.setFailedCallsInARow(2);
        PersonHold.hold(state, "something");

        PersonHold.release(state);

        assertNull(state.getNeedsPerson());
        assertEquals(0, state.getErrorsInARow());
        assertEquals(0, state.getFailedCallsInARow());
        assertFalse(PersonHold.anythingToRelease(state));
    }

    @Test
    @DisplayName("Forge throwing the same way three times running goes to a person")
    void repeatedErrorsGoToAPerson() {
        RunState state = fresh();
        assertFalse(PersonHold.error(state, "boom"));
        assertFalse(PersonHold.error(state, "boom"));
        assertTrue(PersonHold.error(state, "boom"));
        assertTrue(state.getNeedsPerson().contains("boom"), state.getNeedsPerson());
    }

    // ------------------------------------------------------------ what an agent writes

    @Test
    @DisplayName("an overview field holding prose fails the gate with a reason instead of throwing")
    void proseOverviewIsNotAPath() throws IOException {
        // Verbatim from 004's handoff. On Windows the colon made it an illegal path, resolving it
        // threw after every acceptance command had passed, and the run was retried for three days.
        AtomicInteger calls = new AtomicInteger();
        Agents agents = agents(new Scripted(AgentKind.CODEX, calls,
                writes("Main.java", "class Main {}"),
                Signals.resultOf(new ProcResult(0, "", "", false, Duration.ofMinutes(5)), """
                        {"status":"partial","summary":"s","completed":[],"remaining":[],
                         "sessions_needed":1,
                         "overview":"not verified: workspace access failed before source inspection"}
                        """)));

        RunState state = advance(spec(1), fresh(), agents);

        assertEquals(Phase.PARKED, state.getPhase(), "max_sessions is 1, so the gate's word is final");
        assertEquals("overview class", state.getLastFailure().command());
        assertTrue(state.getLastFailure().output().contains("not a path inside the repository"),
                state.getLastFailure().output());
    }

    // ------------------------------------------------------------ stopping

    @Test
    @DisplayName("a requested stop takes effect between two steps, before the next turn starts")
    void stopsBetweenSteps() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        Agents agents = agents(new Scripted(AgentKind.CODEX, calls,
                writes("Main.java", "class Main {}"),
                Signals.resultOf(new ProcResult(0, "", "", false, Duration.ofMinutes(5)), "done")));

        // Asked to stop once the first turn has run: the gate that follows must not start, and
        // nor must the repair turn after it.
        RunState state = advance(spec(12), fresh(), agents, () -> calls.get() > 0);

        assertEquals(1, calls.get());
        assertEquals(Phase.GATING, state.getPhase());
        assertEquals(Phase.GATING, RunStore.load(paths.stateFile()).orElseThrow().getPhase(),
                "what the next start resumes from must be on disk");
    }
}
