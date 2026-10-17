package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.design.DesignStudio;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.plan.PlanParser;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.Progress;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityReport;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the state machine with stub agents, so the pipeline's own logic is verified without
 * spending subscription quota or depending on what a model happens to do today.
 */
class OrchestratorTest {

    @TempDir
    Path root;

    private RunPaths paths;

    @BeforeEach
    void setUp() {
        paths = new RunPaths(root.resolve("runs/001"));
        seedPlan();
    }

    // ---------------------------------------------------------------- stubs

    /** An agent that performs a scripted side effect and returns a scripted outcome. */
    private record StubAgent(AgentKind kind, Consumer<Path> effect, AgentResult result)
            implements Agent {

        @Override
        public AgentResult run(AgentTask task) {
            effect.accept(task.cwd());
            return result;
        }
    }

    /** The overview class every accepted project must leave behind. */
    private static final String OVERVIEW = "Pipeline.java";

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

    /** Writes the overview, and the run file, alongside whatever else the turn produced. */
    private static void writeOverview(Path repo) {
        try {
            Files.writeString(repo.resolve(OVERVIEW), OVERVIEW_BODY);
            Files.writeString(repo.resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentResult completed(String message) {
        return new AgentResult(
                AgentOutcome.COMPLETED, message,
                new ProcResult(0, message, "", false, Duration.ofSeconds(1)), null);
    }

    private static AgentResult rateLimited(Instant resetAt) {
        return new AgentResult(
                AgentOutcome.RATE_LIMITED, "You've hit your usage limit.",
                new ProcResult(1, "", "", false, Duration.ofSeconds(1)), resetAt);
    }

    private static final AgentResult CLEAN_REVIEW = completed("{\"findings\":[]}");

    /** A finished turn: reports the overview class, as an accepted project must. */
    private static AgentResult finished() {
        return completed("""
                Done.
                {"status":"complete","summary":"built","completed":["all of it"],
                 "remaining":[],"sessions_needed":0,"overview":"Pipeline.java"}
                """);
    }

    private Agents agentsWhere(Agent builder, Agent reviewer) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(builder.kind(), builder);
        map.put(reviewer.kind(), reviewer);
        return new Agents(map);
    }

    private static void writesNothing(Path repo) {
        // The agent that does no work at all — the case the gate exists to catch.
    }

    private static Consumer<Path> writes(String name, String content) {
        return repo -> {
            try {
                Files.writeString(repo.resolve(name), content);
                writeOverview(repo);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    // ---------------------------------------------------------------- fixtures

    private Spec spec(String acceptance, int maxRounds) throws IOException {
        return spec(acceptance, maxRounds, 12);
    }

    private Spec spec(String acceptance, int maxRounds, int maxSessions) throws IOException {
        Path file = Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: claude
                acceptance:
                  - %s
                max_repair_rounds: %d
                max_sessions: %d
                ---
                Build the thing.
                """.formatted(acceptance, maxRounds, maxSessions));
        return SpecLoader.load(file);
    }

    private RunState freshState() {
        return RunState.builder()
                .id("001")
                .title("Test project")
                .assignedTo(AgentKind.CLAUDE)
                .createdAt(Instant.now())
                .build();
    }

    private RunState advance(Spec spec, RunState state, Agents agents) {
        return new Orchestrator(spec, paths, state, agents, new Workspace(paths, "house rules"),
                new Wishlist(List.of(spec)), TIERS, null)
                .advance();
    }

    /**
     * The ladders, shaped like the real ones.
     *
     * <p>Named so a test can assert which rung a turn ran on: "cheap" is never what a plan runs on
     * and "best" is never what a repair starts on.
     */
    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "middling", "strong", "best"),
            "CODEX", List.of("codex-cheap", "codex-middling", "codex-strong", "codex-best")));

    /**
     * A plan already on disk, which is what most of these tests want.
     *
     * <p>The planning turn is a phase of its own with its own tests below; seeding its output here
     * keeps every other test exercising the thing it was written for rather than paying for a
     * planning turn first.
     */
    private void seedPlan() {
        try {
            Files.createDirectories(paths.root());
            Files.writeString(paths.planFile(), """
                    ## Approach
                    Build it in one class.
                    """);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- tests

    // ------------------------------------------------------- session accounting

    @Test
    @DisplayName("a rate-limited call is not charged to the session budget")
    void rejectedCallsCostNoSession() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing,
                        rateLimited(Instant.now().plusSeconds(3600))),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        // Observed for real: one project recorded seven sessions, and seven of the eight calls
        // behind that number were 2.5-second "you've hit your session limit" replies. The budget
        // exists to stop a project running forever, not to charge it for the vendor's scheduling.
        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertEquals(0, state.getSessions(), "nothing reached a model, so nothing was spent");
        assertEquals(0, state.getSessionsOnConversation());
    }

    // ------------------------------------------------------------------ security

    /** Built at runtime so no credential-shaped literal sits in this source file. */
    private static final String AWS_KEY = "AKIA" + "Q7XK2M9PLR4TN8WZ";

    /** A builder whose first turn commits a key; {@code removesIt} decides what the repair does. */
    private Agent builderLeakingAKey(List<String> briefs, boolean removesIt) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                writeOverview(task.cwd());
                try {
                    boolean first = briefs.size() == 1;
                    Files.writeString(task.cwd().resolve("Keys.java"), first || !removesIt
                            ? "class Keys { String aws = \"" + AWS_KEY + "\"; }"
                            : "class Keys { String aws = System.getenv(\"AWS_ACCESS_KEY_ID\"); }");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return finished();
            }
        };
    }

    /** A reviewer answering both reviews cleanly, counting the security ones. */
    private Agent cleanReviewerCounting(AtomicInteger securityCalls) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                if (task.brief().startsWith("Review this project for security")) {
                    securityCalls.incrementAndGet();
                }
                return CLEAN_REVIEW;
            }
        };
    }

    @Test
    @DisplayName("a committed key earns one security repair turn, then the project goes on to review")
    void securityFindingEarnsOneRepair() throws IOException {
        List<String> briefs = new ArrayList<>();
        AtomicInteger securityCalls = new AtomicInteger();

        RunState state = advance(spec("exit 0", 2), freshState(),
                agentsWhere(builderLeakingAKey(briefs, true), cleanReviewerCounting(securityCalls)));

        assertEquals(Phase.DONE, state.getPhase(), state.getLastError());
        assertEquals(2, briefs.size(), "the build, then exactly one security repair");
        assertTrue(briefs.get(1).contains("AWS access key id"), briefs.get(1));
        assertTrue(state.isSecurityRepairUsed());
        assertEquals(0, state.getRepairRounds(), "a security finding is not a failed gate");

        var report = SecurityReport.load(paths.securityFile());
        assertTrue(report.serious().isEmpty(), "the key left the tree: " + report.serious());
        assertEquals(1, report.forRuling().size(),
                "it is still in history, so revoking it is still the person's decision");
        assertEquals(2, securityCalls.get(), "the repair changed the code, so it was read again");
    }

    @Test
    @DisplayName("a serious finding the repair did not fix goes to the person, and does not park the project")
    void unfixedSecurityFindingIsNotParked() throws IOException {
        List<String> briefs = new ArrayList<>();
        AtomicInteger securityCalls = new AtomicInteger();

        RunState state = advance(spec("exit 0", 2), freshState(),
                agentsWhere(builderLeakingAKey(briefs, false), cleanReviewerCounting(securityCalls)));

        assertEquals(Phase.DONE, state.getPhase(), state.getLastError());
        assertEquals(2, briefs.size(), "one repair, not a loop");
        var report = SecurityReport.load(paths.securityFile());
        assertEquals(1, report.serious().size());
        assertTrue(ReviewItem.of(state.getPlan(), null, report).stream()
                .anyMatch(item -> item.source().equals(ReviewItem.SECURITY)));
        assertEquals(1, securityCalls.get(),
                "the repair left the code as it was, so the AI review was not paid for twice");
    }

    @Test
    @DisplayName("what each build turn spent is added to the project")
    void tokensAreRecordedPerProject() throws IOException {
        // The usage ledger is per vendor, so which project spent a window had no answer.
        String closing = "{\"type\":\"result\",\"total_cost_usd\":1.5,\"usage\":{\"input_tokens\":100,"
                + "\"cache_read_input_tokens\":9000,\"cache_creation_input_tokens\":0,"
                + "\"output_tokens\":900}}";
        AgentResult turn = new AgentResult(AgentOutcome.COMPLETED, finished().finalMessage(),
                new ProcResult(0, closing, "", false, Duration.ofSeconds(1)), null);

        RunState state = advance(spec("exit 0", 2), freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), turn),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.DONE, state.getPhase());
        assertEquals(10_000, state.getTokensSpent());
    }

    @Test
    @DisplayName("time spent stepping is accumulated onto the project")
    void workIsTimed() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        // Deliberately not a duration assertion — how long a scaffold, a gate and a review take on
        // a given machine is not something a test can pin without becoming flaky. What matters is
        // that the counter moves at all, because the failure it replaces was a clock that ran
        // whether or not anything happened.
        assertTrue(state.getWorkedMillis() > 0, "a full pipeline recorded no work time");
    }

    @Test
    @DisplayName("a rate-limited turn still records the time it cost")
    void failedTurnsAreTimedToo() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing,
                        rateLimited(Instant.now().plusSeconds(3600))),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        // The scaffold ran even though the turn was refused, and a project that has burned time
        // getting nowhere is exactly the one worth being able to see.
        assertTrue(advance(spec, freshState(), agents).getWorkedMillis() > 0);
    }

    @Test
    @DisplayName("a call that did real work is charged as a session")
    void realTurnsStillCost() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(1, state.getSessions());
    }

    // ------------------------------------------------------- assignment

    @Test
    @DisplayName("editing a spec's assign: moves a project that has not started yet")
    void explicitAssignmentIsHonouredBeforeTheFirstTurn() throws IOException {
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CODEX, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        // Assigned to Claude when the run was created, as an auto-assignment would have left it.
        // The spec on disk now says codex, which is a person changing their mind afterwards.
        RunState state = freshState();
        state.setAssignedTo(AgentKind.CLAUDE);

        RunState after = advance(codexSpec(), state, agents);

        assertEquals(AgentKind.CODEX, after.getAssignedTo(),
                "the one control a person has over which subscription a project spends");
    }

    @Test
    @DisplayName("a project already under way is moved when the person changes its vendor")
    void assignmentIsChangedMidBuildWhenAsked() throws IOException {
        // It used to be refused, to keep the conversation; 004 then sat with a vendor that could not
        // run its browser tests. The person changing assign: has weighed that.
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, writes("Main.java", "class Main {}"), finished()));

        RunState state = freshState();
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setSessions(3);

        RunState after = advance(codexSpec(), state, agents);

        assertEquals(AgentKind.CODEX, after.getAssignedTo(), "the person decides which subscription a project spends");
    }

    private Spec codexSpec() throws IOException {
        Path file = Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: codex
                acceptance:
                  - exit 0
                max_repair_rounds: 2
                max_sessions: 12
                ---
                Build the thing.
                """);
        return SpecLoader.load(file);
    }

    // ------------------------------------------------------- conversation compaction

    /**
     * An agent that genuinely advances the work each turn.
     *
     * <p>Rewriting identical bytes is not progress, and the pipeline is right to treat it as a
     * repair situation rather than a continuation — so a compaction test that wants the
     * continuation path has to actually build something new each time.
     */
    private static Consumer<Path> writesSomethingNew() {
        AtomicInteger turn = new AtomicInteger();
        return repo -> {
            try {
                int n = turn.incrementAndGet();
                Files.writeString(repo.resolve("Part" + n + ".java"), "class Part" + n + " {}");
                writeOverview(repo);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** A turn that finished a chunk of work and left notes behind. */
    private static AgentResult partialWithDigest(String digest) {
        return completed("""
                Made progress.
                {"status":"partial","summary":"built the parser",
                 "completed":["lexer"],"remaining":[{"step":"the evaluator","estimate_minutes":30}],
                 "sessions_needed":1,"overview":"Pipeline.java","digest":"%s"}
                """.formatted(digest));
    }

    @Test
    @DisplayName("the digest is kept and written where a person can read it")
    void digestIsRecorded() throws IOException {
        Spec spec = spec("exit 1", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"),
                        partialWithDigest("Built as a pipeline. Tried a visitor, too indirect.")),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals("Built as a pipeline. Tried a visitor, too indirect.",
                state.getPlan().getDigest());
        assertTrue(Files.isRegularFile(paths.digestFile()),
                "the digest belongs beside the report, not only inside state.json");
        assertEquals("Built as a pipeline. Tried a visitor, too indirect.",
                Files.readString(paths.digestFile()));
    }

    @Test
    @DisplayName("the conversation is dropped once it has run long enough")
    void conversationIsDroppedWhenDue() throws IOException {
        Spec spec = spec("exit 1", 9);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writesSomethingNew(),
                        partialWithDigest("how it is built")),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = freshState();
        state.setClaudeSession("conversation-1");

        // One advance drives the machine until the session budget is spent: twelve turns, with
        // compact_after_sessions defaulting to three.
        state = advance(spec, state, agents);

        assertEquals(12, state.getSessions(), "the session budget is what bounds this run");
        assertEquals(4, state.getCompactions(), "twelve turns, three to a conversation");
        assertEquals(0, state.getSessionsOnConversation(), "the counter restarts with the talk");
        assertNull(state.getClaudeSession(), "a dropped conversation must not be resumed");
        assertEquals("how it is built", state.getPlan().getDigest(),
                "the notes are what survives, so they must outlive the conversation");
    }

    @Test
    @DisplayName("a conversation is never dropped while there is nothing to carry across")
    void noDigestMeansNoCompaction() throws IOException {
        Spec spec = spec("exit 1", 9);
        Agents agents = agentsWhere(
                // Reports a usable plan every turn, but never writes a digest.
                new StubAgent(AgentKind.CLAUDE, writesSomethingNew(),
                        completed("""
                                {"status":"partial","summary":"working","completed":["a bit"],
                                 "remaining":[{"step":"more","estimate_minutes":10}],
                                 "sessions_needed":1,"overview":"Pipeline.java"}
                                """)),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = freshState();
        state.setClaudeSession("conversation-1");

        state = advance(spec, state, agents);

        assertEquals(0, state.getCompactions(), "there was nothing to carry across");
        assertEquals("conversation-1", state.getClaudeSession(),
                "without notes, the conversation is all there is and must be kept");
        assertEquals(12, state.getSessionsOnConversation(), "nothing reset the conversation");
    }


    @Test
    @DisplayName("a project whose acceptance passes reaches DONE")
    void happyPath() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.DONE, state.getPhase());
        assertNull(state.getLastError());
        assertTrue(Files.isRegularFile(paths.repo().resolve("Main.java")));
    }

    @Test
    @DisplayName("an agent that claims success but fails the gate is PARKED, not DONE")
    void agentSelfReportCannotPassTheGate() throws IOException {
        Spec spec = spec("exit 1", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing,
                        completed("All done! Everything works perfectly.")),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.PARKED, state.getPhase());
        assertNotNull(state.getLastError());
        assertTrue(state.getLastError().contains("repair rounds"), state.getLastError());
    }

    /** Builds, records each brief, and writes a run file only when a brief asks for one. */
    private record ToldBuilder(List<String> briefs, boolean obeys) implements Agent {

        @Override
        public AgentKind kind() {
            return AgentKind.CLAUDE;
        }

        @Override
        public AgentResult run(AgentTask task) {
            briefs.add(task.brief());
            try {
                Files.writeString(task.cwd().resolve(OVERVIEW), OVERVIEW_BODY);
                if (obeys && task.brief().contains("Also owed in this turn: a working `run.bat`")) {
                    Files.writeString(task.cwd().resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return finished();
        }
    }

    @Test
    @DisplayName("a project with no run file is told so in its brief, and adds it without failing a gate first")
    void aProjectWithNoRunFileIsToldInItsBrief() throws IOException {
        // What a project begun before the rule looks like: everything else in order, and nothing
        // to double-click. Its agent is resuming a conversation and will not reread the rules.
        Spec spec = spec("exit 0", 2);
        List<String> briefs = new ArrayList<>();
        Agents agents = agentsWhere(new ToldBuilder(briefs, true),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.DONE, state.getPhase(), state.getLastError());
        assertEquals(0, state.getRepairRounds(), "it should not have cost a repair round to find out");
        assertTrue(Files.isRegularFile(paths.repo().resolve(RunFile.NAME)));
    }

    @Test
    @DisplayName("a project that never gets a run file does not pass, and the failure says what is missing")
    void aProjectThatNeverGetsARunFileDoesNotPass() throws IOException {
        Spec spec = spec("exit 0", 1);
        List<String> briefs = new ArrayList<>();
        Agents agents = agentsWhere(new ToldBuilder(briefs, false),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.PARKED, state.getPhase());
        assertEquals("run file: run.bat", state.getLastFailure().command());
        assertTrue(briefs.getLast().contains("no `run.bat` in the repository root"),
                "the repair brief should carry the gate's reason:\n" + briefs.getLast());
    }

    @Test
    @DisplayName("an edit to the house rules reaches a project already under way")
    void houseRulesAreRefreshedBeforeEachTurn() throws IOException {
        // They were written once, at scaffold. So every rule added afterwards applied to projects
        // started afterwards and to nothing else, and the six running here each carried the rules
        // as they stood on the day they began. Nothing said so; the file was simply stale.
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = freshState();
        new Orchestrator(spec, paths, state, agents, new Workspace(paths, "the old rules"),
                new Wishlist(List.of(spec)), TIERS, null).advance();
        assertTrue(Files.readString(paths.repo().resolve("AGENTS.md")).contains("the old rules"));

        // The same project, a later session, with the rules since rewritten.
        state.setPhase(Phase.CONTINUING);
        new Orchestrator(spec, paths, state, agents, new Workspace(paths, "a rule added later"),
                new Wishlist(List.of(spec)), TIERS, null).advance();

        assertTrue(Files.readString(paths.repo().resolve("AGENTS.md")).contains("a rule added later"),
                "the workspace is still carrying the rules from the day it was created");
        assertTrue(Files.readString(paths.repo().resolve("CLAUDE.md")).contains("a rule added later"),
                "both filenames, or one vendor gets a weaker brief than the other");
    }

    @Test
    @DisplayName("a cross-review cannot turn a parked project into a finished one")
    void reviewingParkedWorkDoesNotFinishIt() throws IOException {
        // Found on a real project here. It parked with its acceptance still failing, was sent for
        // a cross-review from the dashboard — a reasonable thing to want, a second opinion on why
        // it is stuck — and came back DONE, with the failing command still recorded beside it.
        // Nothing had been rebuilt and nothing had been re-run: a button had promoted failing work
        // to finished. The gate decides done. A review is an opinion, not a second verdict.
        Spec spec = spec("exit 1", 0);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState parked = advance(spec, freshState(), agents);
        assertEquals(Phase.PARKED, parked.getPhase());
        assertNotNull(parked.getLastFailure(), "the gate must have recorded why");

        // Precisely what the Review button does to a terminal project.
        parked.setPhase(Phase.REVIEWING);
        parked.setLastError(null);
        RunState reviewed = advance(spec, parked, agents);

        assertEquals(Phase.PARKED, reviewed.getPhase(),
                "a clean review promoted work whose gate never passed");
        assertTrue(reviewed.getLastError().contains("still failing"), reviewed.getLastError());
        assertNotNull(reviewed.getLastFailure(), "the reason it parked must survive the review");
    }

    @Test
    @DisplayName("repairs are bounded by max_repair_rounds")
    void stopsAfterTheAllowedRepairRounds() throws IOException {
        Spec spec = spec("exit 1", 3);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing, completed("fixed")),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.PARKED, state.getPhase());
        assertEquals(3, state.getRepairRounds());
    }

    @Test
    @DisplayName("the failing command is persisted so a later process can build the repair prompt")
    void recordsTheFailureForTheRepairPrompt() throws IOException {
        Spec spec = spec("echo DISTINCTIVE_MARKER && exit 4", 1);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing, completed("done")),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(4, state.getLastFailure().exitCode());
        assertTrue(state.getLastFailure().output().contains("DISTINCTIVE_MARKER"));
    }

    @Test
    @DisplayName("a quota limit suspends rather than consuming a repair round")
    void suspendsOnRateLimit() throws IOException {
        Instant resetAt = Instant.now().plus(Duration.ofHours(2));
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing, rateLimited(resetAt)),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertEquals(Phase.SCAFFOLDED, state.getResumePhase(), "must resume at the build it never ran");
        assertEquals(resetAt, state.getRetryAfter());
        assertEquals(0, state.getRepairRounds(), "a quota limit is not the project's fault");
    }

    @Test
    @DisplayName("a suspended run resumes from state.json once its window reopens")
    void resumesFromDiskAfterSuspension() throws IOException {
        Spec spec = spec("exit 0", 2);

        Agents unavailable = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, OrchestratorTest::writesNothing,
                        rateLimited(Instant.now().minusSeconds(1))),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));
        advance(spec, freshState(), unavailable);

        // A completely new process: nothing carried over but the state file on disk.
        RunState reloaded = RunStore.load(paths.stateFile()).orElseThrow();
        assertEquals(Phase.SUSPENDED, reloaded.getPhase());

        Agents available = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));
        RunState finished = advance(spec, reloaded, available);

        assertEquals(Phase.DONE, finished.getPhase());
        assertTrue(Files.isRegularFile(paths.repo().resolve("Main.java")));
    }

    @Test
    @DisplayName("a blocking finding that survives its attempts waits for a person, not DONE")
    void blockingReviewIsNeverWavedThrough() throws IOException {
        // It used to buy exactly one repair turn and then finish as DONE with the finding still on
        // it. Every blocking finding is now mandatory: two attempts, then the person decides.
        Spec spec = spec("exit 0", 2);
        AgentResult blocking = completed(
                "{\"findings\":[{\"severity\":\"blocking\",\"file\":\"Main.java\","
                        + "\"summary\":\"drops input silently\",\"detail\":\"no error path\"}]}");

        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, blocking));

        RunState state = advance(spec, freshState(), agents);

        assertEquals(Phase.REVIEW_REPAIR, state.getPhase(), "must not loop on a reviewer that never relents");
        assertTrue(state.getNeedsPerson().contains("drops input silently"), state.getNeedsPerson());
        assertEquals(2, state.getBlockingFindings().getFirst().getAttempts());
        assertEquals(2, state.getReviewRepairs());
        assertTrue(state.isReviewRepairUsed());
    }

    @Test
    @DisplayName("work left by a crashed turn is committed and gated, not rebuilt")
    void recoversWorkFromAnInterruptedTurn() throws IOException {
        Spec spec = spec("exit 0", 2);
        Workspace workspace = new Workspace(paths, "house rules");

        // Scaffold, then simulate the agent writing files and the process dying before the commit.
        RunState state = freshState();
        state.setBaselineCommit(workspace.scaffold(spec));
        state.setPhase(Phase.SCAFFOLDED);
        state.setTurnInFlight(true);
        Files.writeString(paths.repo().resolve("Main.java"), "class Main {}");
        writeOverview(paths.repo());
        state.setPlan(PlanParser.parse(finished().finalMessage()));

        Agent mustNotRun = new StubAgent(AgentKind.CLAUDE, repo -> {
            throw new AssertionError("rebuilt work that was already on disk");
        }, completed("unused"));

        RunState finished = advance(spec, state,
                agentsWhere(mustNotRun,
                        new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.DONE, finished.getPhase());
        assertEquals(0, finished.getRepairRounds(), "a crash is not the project's fault");
    }

    @Test
    @DisplayName("an interrupted turn's failing gate costs a session, not a repair round")
    void interruptedTurnsDoNotSpendRepairRounds() throws IOException {
        // 005, verbatim in shape: an agent ran Get-Process java | Stop-Process -Force, killing Forge
        // mid-turn. Each recovered turn had no handoff, so its gate failed on the overview whatever
        // was built, and four recoveries spent all four repair rounds. It parked with sessions: 0.
        Spec spec = spec("exit 0", 1);
        Workspace workspace = new Workspace(paths, "house rules");
        RunState state = freshState();
        state.setBaselineCommit(workspace.scaffold(spec));
        state.setPhase(Phase.SCAFFOLDED);
        state.setRepairRounds(1);
        state.setTurnInFlight(true);
        Files.writeString(paths.repo().resolve("Main.java"), "class Main {}");

        List<String> briefs = new ArrayList<>();
        Agent repairs = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                writeOverview(task.cwd());
                return finished();
            }
        };

        RunState finished = advance(spec, state, agentsWhere(repairs,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.DONE, finished.getPhase(), finished.getLastError());
        assertEquals(1, briefs.size(), "the interrupted work is repaired, once");
        assertEquals(1, finished.getRepairRounds(), "the round already spent is the only one");
        assertEquals(2, finished.getSessions(), "the interrupted turn and the repair both ran");
    }

    @Test
    @DisplayName("gate leftovers must not be mistaken for interrupted work and skip a repair")
    void gateArtefactsDoNotSkipTheOwedRepairTurn() throws IOException {
        Spec spec = spec("exit 0", 2);
        Workspace workspace = new Workspace(paths, "house rules");

        // A repair is owed, and the tree is dirty with something a gate produced that no
        // scaffold gitignore anticipated — the shape that previously skipped the repair.
        RunState state = freshState();
        state.setBaselineCommit(workspace.scaffold(spec));
        state.setPhase(Phase.REPAIRING);
        state.setRepairRounds(1);
        state.setLastFailure(new CommandResult(
                "exit 1", 1, "boom", false, Duration.ofSeconds(1), "gate-1.bat"));
        Files.createDirectories(paths.repo().resolve(".unexpected-cache"));
        Files.writeString(paths.repo().resolve(".unexpected-cache/data"), "gate leftovers");

        AtomicBoolean repaired = new AtomicBoolean();
        RunState finished = advance(spec, state, agentsWhere(
                new StubAgent(AgentKind.CLAUDE,
                        repo -> { repaired.set(true); writeOverview(repo); }, finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertTrue(repaired.get(), "the owed repair turn was skipped");
        assertEquals(Phase.DONE, finished.getPhase());
    }

    /** A handoff saying the work is unfinished. */
    private static AgentResult partial(String nextStep) {
        return completed("""
                Progress so far.

                {"status":"partial",
                 "summary":"part done",
                 "completed":["a bit"],
                 "remaining":[{"step":"%s","detail":"carry on","estimate_minutes":20}],
                 "sessions_needed":2}
                """.formatted(nextStep));
    }

    // ------------------------------------------------- Check again, and a clip owed under two names

    private static final Feature CUSTOMIZATION = new Feature("Garage customization", "");
    private static final Feature TUNING = new Feature("Garage tuning", "");

    private void ruled(Feature feature, String verdict) {
        List<Decision> rulings = new ArrayList<>(DecisionStore.load(paths.featuresFile()));
        rulings.removeIf(ruling -> ruling.getItemId().equals(feature.id()));
        rulings.add(Decision.of(feature.id(), feature.name(), verdict, ""));
        DecisionStore.save(paths.featuresFile(), rulings);
    }

    /** Fails the test if Check again ever reaches the builder: it is not meant to start a session. */
    private static Agent untouchableBuilder() {
        return new StubAgent(AgentKind.CLAUDE, repo -> {
            throw new AssertionError("a gate checked again must not start a session");
        }, finished());
    }

    /** Puts a parked project back to its gate, the way Check again does. */
    private static RunState checkedAgain(RunState parked) {
        parked.setPhase(Phase.GATING);
        parked.setGateOnly(true);
        parked.setLastError(null);
        return parked;
    }

    @Test
    @DisplayName("a gate checked again runs no session, and parks again if it still fails")
    void aGateCheckedAgainSpendsNoSession() throws IOException {
        Spec spec = spec("exit 1", 1, 1);
        Agent reviewer = new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW);
        RunState parked = advance(spec, freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        assertEquals(Phase.PARKED, parked.getPhase());
        int sessions = parked.getSessions();

        RunState again = advance(spec, checkedAgain(parked), agentsWhere(untouchableBuilder(), reviewer));

        assertEquals(Phase.PARKED, again.getPhase());
        assertEquals(sessions, again.getSessions(), "checking the gate is not a session");
        assertFalse(again.isGateOnly());
        assertTrue(again.getLastError().contains("checked again and still fails"), again.getLastError());
    }

    @Test
    @DisplayName("a gate checked again that passes carries on as any passing project does")
    void aGateCheckedAgainThatPassesCarriesOn() throws IOException {
        // Owed a clip nothing films, so the gate fails on that alone, and parks on the session cap.
        Spec spec = spec("echo built", 1, 1);
        ruled(CUSTOMIZATION, Features.APPROVED);
        Agent reviewer = new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW);
        RunState parked = advance(spec, freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        assertEquals(Phase.PARKED, parked.getPhase());
        assertTrue(parked.getLastFailure().command().startsWith("recordings:"),
                parked.getLastFailure().command());

        // The person rules it out, and asks the gate again.
        ruled(CUSTOMIZATION, Features.DECLINED);
        RunState again = advance(spec, checkedAgain(parked), agentsWhere(untouchableBuilder(), reviewer));

        assertEquals(Phase.DONE, again.getPhase(), String.valueOf(again.getLastError()));
        assertEquals(1, again.getSessions());
    }

    @Test
    @DisplayName("a clip moved from one approved name to the other parks the project for a person")
    void aClipChasedBetweenTwoNamesParks() throws IOException {
        // 003, in two sessions: one test filming one feature approved under two names, its clip
        // saved under one name and then renamed to the other. Twenty-three sessions went that way.
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        // Not "if not exist artifacts mkdir artifacts & copy": cmd makes the copy part of the if, so
        // from the second gate on, with the folder already there, nothing was copied at all.
        Spec spec = spec(windows ? "mkdir artifacts 2>nul & copy /Y film\\* artifacts\\ >nul"
                : "mkdir -p artifacts && cp film/* artifacts/", 3, 6);
        ruled(CUSTOMIZATION, Features.APPROVED);
        ruled(TUNING, Features.APPROVED);
        AtomicInteger turn = new AtomicInteger();
        Agent builder = new StubAgent(AgentKind.CLAUDE, repo -> {
            try {
                writeOverview(repo);
                Feature filmed = turn.incrementAndGet() % 2 == 1 ? CUSTOMIZATION : TUNING;
                Path film = repo.resolve("film");
                Files.createDirectories(film);
                try (var old = Files.list(film)) {
                    for (Path clip : old.toList()) {
                        Files.delete(clip);
                    }
                }
                String name = filmed.recordingPath(".webm");
                Clips.write(film.resolve(name.substring(name.lastIndexOf('/') + 1)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, finished());

        RunState state = advance(spec, freshState(), agentsWhere(builder,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.PARKED, state.getPhase());
        assertEquals(2, state.getSessions(), "the second gate is where a person should be asked");
        assertTrue(state.getLastError().contains("moved between names"), state.getLastError());
        assertTrue(state.getLastError().contains("\"Garage customization\""), state.getLastError());
    }

    // ------------------------------------------------- a spec changed under a project

    /** A builder that does a little real work each turn, and keeps every brief it was given. */
    private record BriefsKept(List<String> briefs, AtomicInteger turn) implements Agent {
        @Override
        public AgentKind kind() {
            return AgentKind.CLAUDE;
        }

        @Override
        public AgentResult run(AgentTask task) {
            briefs.add(task.brief());
            writes("step" + turn.incrementAndGet() + ".java", "class Step {}").accept(task.cwd());
            return finished();
        }
    }

    /** The same spec with its prose changed, as the person would change it in the editor. */
    private Spec edited(Spec spec, String prose) throws IOException {
        Path file = root.resolve("001-test.md");
        Files.writeString(file, Files.readString(file).replace("Build the thing.", prose));
        return SpecLoader.load(file);
    }

    @Test
    @DisplayName("the session after the person changes the spec is told what changed, and only that one")
    void aChangedSpecIsToldToTheNextSession() throws IOException {
        Spec spec = spec("exit 1", 1, 8);
        Agent reviewer = new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW);
        List<String> briefs = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        RunState parked = advance(spec, freshState(), agentsWhere(new BriefsKept(briefs, turn), reviewer));
        assertEquals(Phase.PARKED, parked.getPhase());
        assertTrue(briefs.stream().noneMatch(brief -> brief.contains("The spec has changed")),
                "nothing changed, so nothing is said");

        // The person edits the spec, and asks for another session.
        Spec changed = edited(spec, "Build the thing, with a dealer who gives one car for watching an ad.");
        parked.setPhase(Phase.CONTINUING);
        parked.setRepairRounds(0);
        briefs.clear();
        RunState after = advance(changed, parked, agentsWhere(new BriefsKept(briefs, turn), reviewer));

        assertTrue(briefs.size() >= 2, "a repair turn followed, so the second brief can be checked: " + briefs.size());
        String told = briefs.get(0);
        assertTrue(told.startsWith("## The spec has changed"), "it outranks the rest, so it comes first:\n" + told);
        assertTrue(told.contains("+Build the thing, with a dealer who gives one car"), told);
        assertTrue(told.contains("-Build the thing."), told);
        assertTrue(briefs.get(1).indexOf("The spec has changed") < 0, "told once, not every turn");
        assertTrue(Files.readString(paths.repo().resolve("SPEC.md")).contains("a dealer who gives one car"));
        assertNull(after.getSpecChange());
    }

    @Test
    @DisplayName("a turn refused for quota does not use up the notice of a changed spec")
    void aRefusedTurnKeepsTheNotice() throws IOException {
        Spec spec = spec("exit 1", 1, 8);
        Agent reviewer = new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW);
        RunState parked = advance(spec, freshState(), agentsWhere(
                new BriefsKept(new ArrayList<>(), new AtomicInteger()), reviewer));
        assertEquals(Phase.PARKED, parked.getPhase());

        Spec changed = edited(spec, "Build the thing, with real ads.");
        parked.setPhase(Phase.CONTINUING);
        parked.setRepairRounds(0);
        RunState refused = advance(changed, parked, agentsWhere(new StubAgent(AgentKind.CLAUDE,
                OrchestratorTest::writesNothing, rateLimited(Instant.now().plusSeconds(3600))), reviewer));

        assertEquals(Phase.SUSPENDED, refused.getPhase());
        assertNotNull(refused.getSpecChange(), "the copy is already current, so this is the only notice left");
        assertTrue(refused.getSpecChange().contains("+Build the thing, with real ads."), refused.getSpecChange());
    }

    // ------------------------------------------------- how much review a visual polish needs

    /**
     * A reviewer that answers each of its three kinds of question, and counts the full reviews.
     *
     * @param scopeSays what it answers when asked whether a polish changed only appearance
     */
    private record ReviewerCounting(AtomicInteger fullReviews, boolean scopeSays) implements Agent {
        @Override
        public AgentKind kind() {
            return AgentKind.CODEX;
        }

        @Override
        public AgentResult run(AgentTask task) {
            if (task.brief().contains("for security problems")) {
                return CLEAN_REVIEW;
            }
            if (task.brief().contains("appearance_only")) {
                return completed("{\"appearance_only\": " + scopeSays + ", \"why\": \""
                        + (scopeSays ? "only colours and spacing" : "it removed the restart rule") + "\"}");
            }
            fullReviews.incrementAndGet();
            return CLEAN_REVIEW;
        }
    }

    /** A finished project, then sent back to be restyled the way Polish sends it, from HEAD. */
    private RunState finishedThenPolished(Spec spec, Agents agents) {
        RunState done = advance(spec, freshState(), agents);
        assertEquals(Phase.DONE, done.getPhase(), String.valueOf(done.getLastError()));
        done.setPhase(Phase.CONTINUING);
        done.setUserFeedback("Polish how this looks.");
        done.setPolishBase(new Git(paths.repo(), paths.gitLog()).head());
        return done;
    }

    @Test
    @DisplayName("a polish that changed only stylesheets goes back to the person without a full review")
    void aStylesheetPolishIsNotReviewedAgain() throws IOException {
        Spec spec = spec("echo built", 1, 6);
        AtomicInteger fullReviews = new AtomicInteger();
        Agent reviewer = new ReviewerCounting(fullReviews, true);
        RunState polished = finishedThenPolished(spec, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        int before = fullReviews.get();

        RunState state = advance(spec, polished, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("style.css", "body { color: plum; }"), finished()), reviewer));

        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        assertEquals(before, fullReviews.get(), "nothing but the look changed, so nothing was read again");
        assertNull(state.getPolishBase());
        assertTrue(state.getPolishCheck().contains("only stylesheets"), state.getPolishCheck());
    }

    @Test
    @DisplayName("a polish that changed code is asked about once, cheaply, and a yes skips the full review")
    void aCodePolishReadAsAppearanceIsNotReviewedAgain() throws IOException {
        Spec spec = spec("echo built", 1, 6);
        AtomicInteger fullReviews = new AtomicInteger();
        Agent reviewer = new ReviewerCounting(fullReviews, true);
        RunState polished = finishedThenPolished(spec, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        int before = fullReviews.get();

        RunState state = advance(spec, polished, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Screen.java", "class Screen { String colour = \"plum\"; }"),
                        finished()), reviewer));

        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        assertEquals(before, fullReviews.get());
        assertTrue(state.getPolishCheck().contains("appearance only"), state.getPolishCheck());
    }

    @Test
    @DisplayName("a polish that changed more than appearance is reviewed in full, and says why")
    void aPolishThatChangedBehaviourIsReviewedInFull() throws IOException {
        Spec spec = spec("echo built", 1, 6);
        AtomicInteger fullReviews = new AtomicInteger();
        Agent reviewer = new ReviewerCounting(fullReviews, false);
        RunState polished = finishedThenPolished(spec, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        int before = fullReviews.get();

        RunState state = advance(spec, polished, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Rules.java", "class Rules {}"), finished()), reviewer));

        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        assertEquals(before + 1, fullReviews.get(), "it changed more than the look, so it was read in full");
        assertTrue(state.getPolishCheck().contains("removed the restart rule"), state.getPolishCheck());
    }

    @Test
    @DisplayName("a polish built to a picked design is reviewed in full against the picture, however little it changed")
    void aPolishBuiltToADesignIsComparedWithIt() throws IOException {
        // 003: built to "Hard-Edged Die-Cast", it drew the design's detailed cars as flat shapes. It
        // changed little besides the look, so it was waved through as appearance-only, and only the
        // person compared it with the picture.
        Spec spec = spec("echo built", 1, 6);
        List<String> briefs = new CopyOnWriteArrayList<>();
        Agent reviewer = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                return task.brief().contains("## The look it was asked for")
                        ? completed("{\"findings\":[],\"compared_with_design\":true}")
                        : CLEAN_REVIEW;
            }
        };
        RunState polished = finishedThenPolished(spec, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        polished.setPolishDesign("Hard-Edged Die-Cast");
        briefs.clear();

        RunState state = advance(spec, polished, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("style.css", "body { color: plum; }"), finished()), reviewer));

        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
        String full = briefs.stream().filter(b -> b.contains("## The look it was asked for")).findFirst()
                .orElseThrow(() -> new AssertionError("no review compared it with the picture: " + briefs));
        assertTrue(full.contains("\"Hard-Edged Die-Cast\""), full);
        assertTrue(full.contains("`" + DesignStudio.CHOSEN_IN_REPO + "`"), "the picture the builder was given");
        assertTrue(full.contains("`" + DesignStudio.PICTURE_AFTER + "`"), "the builder's own picture of the result");
        assertTrue(state.getPolishCheck().contains("compared with that picture"), state.getPolishCheck());
        assertNull(state.getPolishDesign(), "settled: the next review is not about the picture");
        assertTrue(full.startsWith("Review this change. Do not modify any files. Do not run any commands, except "
                + "to open the two files"), "said first, where the rule against commands is: " + full);
    }

    @Test
    @DisplayName("a polish nobody compared with its picture is held for the person, not finished as if it had been")
    void aPolishNobodyComparedIsHeld() throws IOException {
        // 003's first review against its design said it had not opened the picture or the result,
        // filed that as a note, and the project went to DONE.
        Spec spec = spec("echo built", 1, 6);
        Agent reviewer = new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, completed("{\"findings\":[{\"severity\":\"note\","
                + "\"summary\":\"Visual comparison remains unreviewed\"}],\"compared_with_design\":false}"));
        RunState polished = finishedThenPolished(spec, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()), reviewer));
        polished.setPolishDesign("Hard-Edged Die-Cast");

        RunState state = advance(spec, polished, agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("style.css", "body { color: plum; }"), finished()), reviewer));

        assertTrue(state.getPhase() != Phase.DONE, "nobody looked, so it is not finished: " + state.getPhase());
        assertTrue(state.getNeedsPerson().contains("no reviewer compared the result with the design"),
                state.getNeedsPerson());
        assertEquals("Hard-Edged Die-Cast", state.getPolishDesign(), "still owed a comparison when it resumes");
    }

    @Test
    @DisplayName("a started project moved to another vendor is taken over: a fresh conversation, told whose work it continues")
    void aStartedProjectCanBeTakenOver() throws IOException {
        // 004 was built by Codex, whose sandbox here cannot launch a browser, so it never saw its
        // browser tests fail. Moving it to Claude used to be ignored once it had started.
        Spec spec = spec("test -f A.java", 2, 30);
        seedPlan();
        RunState started = advance(spec, freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("A.java", "class A {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));
        Files.delete(paths.repo().resolve("A.java"));
        started.setAssignedTo(AgentKind.CODEX);
        started.setSessions(16);
        started.setCodexSession("codex-conversation");
        started.setClaudeSession("stale-claude-conversation");
        started.setPhase(Phase.REPAIRING);
        started.setRepairRounds(1);
        started.setLastFailure(new CommandResult("npm run test:e2e", 1, "4 failed", false, Duration.ZERO, "gate-1.bat"));
        List<String> briefs = new CopyOnWriteArrayList<>();
        Consumer<Path> writesA = writes("A.java", "class A {}");
        Agent claude = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                writesA.accept(task.cwd());
                return finished();
            }
        };

        RunState state = advance(spec, started, agentsWhere(claude,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(AgentKind.CLAUDE, state.getAssignedTo(), "the spec says claude, and the person decides");
        assertTrue(briefs.getFirst().startsWith("You are taking this project over from CODEX"), briefs.getFirst());
        assertTrue(briefs.getFirst().contains("Run every acceptance command yourself"), briefs.getFirst());
        assertTrue(briefs.getFirst().contains("4 failed"), "the repair it landed on is still the repair it was");
        assertTrue(briefs.stream().skip(1).noneMatch(b -> b.contains("taking this project over")), "told once");
        assertNull(state.getCodexSession(), "Codex's conversation does not come along");
        assertNull(state.getTakeover());
        assertEquals(Phase.DONE, state.getPhase(), String.valueOf(state.getLastError()));
    }

    @Test
    @DisplayName("needing more time spends sessions, not repair rounds")
    void continuationDoesNotBurnRepairRounds() throws IOException {
        Spec spec = spec("exit 1", 2, 5);
        AtomicInteger turn = new AtomicInteger();

        // Reports unfinished every time and genuinely writes something new each turn.
        Agent builder = new StubAgent(AgentKind.CLAUDE,
                repo -> {
                    try {
                        Files.writeString(repo.resolve("step" + turn.incrementAndGet() + ".java"), "x");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                partial("keep building"));

        RunState state = advance(spec, freshState(), agentsWhere(builder,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.PARKED, state.getPhase());
        assertEquals(5, state.getSessions());
        assertEquals(0, state.getRepairRounds(), "continuing is not repairing");
        assertTrue(state.getLastError().contains("sessions"), state.getLastError());
    }

    @Test
    @DisplayName("claiming progress without making any is treated as being stuck")
    void claimedProgressWithoutChangesStillBurnsRepairRounds() throws IOException {
        Spec spec = spec("exit 1", 2, 20);

        // Says "partial" every time but never writes anything -- the loop this guards against.
        Agent builder = new StubAgent(AgentKind.CLAUDE,
                OrchestratorTest::writesNothing, partial("pretend work"));

        RunState state = advance(spec, freshState(), agentsWhere(builder,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.PARKED, state.getPhase());
        assertEquals(2, state.getRepairRounds());
        assertTrue(state.getLastError().contains("repair rounds"), state.getLastError());
    }

    @Test
    @DisplayName("the agent session is carried into the next turn")
    void resumesTheSameAgentSession() throws IOException {
        Spec spec = spec("exit 1", 1, 3);
        List<String> resumed = new ArrayList<>();

        Agent builder = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                resumed.add(task.resume());
                return partial("more").withSession("session-abc");
            }
        };

        advance(spec, freshState(), agentsWhere(builder,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertNull(resumed.get(0), "the first turn starts a new session");
        assertTrue(resumed.size() > 1, "expected more than one turn");
        assertEquals("session-abc", resumed.get(1), "later turns resume the same session");
    }

    @Test
    @DisplayName("a reviewer that never ran does not count as a clean review")
    void unavailableReviewerDoesNotAccept() throws IOException {
        Spec spec = spec("exit 0", 2);
        Instant resetAt = Instant.now().plus(Duration.ofHours(1));

        // The gate passes, but the reviewing vendor is out of quota. Accepting here would ship a
        // project nobody reviewed -- which is exactly what happened to 003 in practice.
        RunState state = advance(spec, freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, rateLimited(resetAt))));

        assertEquals(Phase.SUSPENDED, state.getPhase());
        assertEquals(Phase.REVIEWING, state.getResumePhase(), "it owes a review, not a rebuild");
        assertEquals(resetAt, state.getRetryAfter());
        assertTrue(state.getLastError().contains("reviewed"), state.getLastError());
    }

    /** Builder Claude and both other vendors as reviewers, the first asked replying with {@code first}. */
    private RunState reviewedBy(AgentResult first, AgentResult second) throws IOException {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE,
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()));
        map.put(AgentKind.CODEX, new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, null));
        map.put(AgentKind.ANTIGRAVITY,
                new StubAgent(AgentKind.ANTIGRAVITY, OrchestratorTest::writesNothing, null));
        List<AgentKind> order = new Agents(map).reviewersFor(AgentKind.CLAUDE);
        map.put(order.get(0), new StubAgent(order.get(0), OrchestratorTest::writesNothing, first));
        map.put(order.get(1), new StubAgent(order.get(1), OrchestratorTest::writesNothing, second));

        return advance(spec("exit 0", 2), freshState(), new Agents(map));
    }

    @Test
    @DisplayName("a reply that cannot be read is not the last word when another reviewer can answer")
    void unreadableReviewAsksTheNextReviewer() throws IOException {
        // What 002, 003 and 006 got: "no JSON object found in the reviewer's reply", counted as a
        // clean review, with Claude and Codex never asked.
        RunState state = reviewedBy(completed("I could not finish reading the diff."),
                completed("{\"findings\":[{\"severity\":\"blocking\",\"summary\":\"draws are lost\"}]}"));

        Review review =
                ReviewStore.load(paths.reviewFile());
        assertNull(review.getParseError(), "the readable review is the one kept");
        assertEquals(1, review.getFindings().size());
        assertTrue(state.isReviewRepairUsed(), "its blocking finding was acted on");
    }

    @Test
    @DisplayName("an unreadable reply still stands when the only other reviewer is out of quota")
    void unreadableReviewOutranksNoReview() throws IOException {
        RunState state = reviewedBy(completed("I could not finish reading the diff."),
                rateLimited(Instant.now().plus(Duration.ofHours(1))));

        Review review =
                ReviewStore.load(paths.reviewFile());
        assertNotNull(review.getParseError());
        assertTrue(!review.isUnavailable(), "the file says who actually ran, not who was refused");
        assertEquals(Phase.DONE, state.getPhase());
    }

    @Test
    @DisplayName("a reviewer that ran and found nothing does accept")
    void cleanReviewStillAccepts() throws IOException {
        Spec spec = spec("exit 0", 2);

        RunState state = advance(spec, freshState(), agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "class Main {}"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(Phase.DONE, state.getPhase());
    }

    @Test
    @DisplayName("your feedback reaches the agent, and only once")
    void deliversUserFeedbackExactlyOnce() throws IOException {
        Spec spec = spec("exit 0", 2);
        List<String> briefs = new ArrayList<>();

        Agent recorder = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                writes("Main.java", "class Main {}").accept(task.cwd());
                return finished();
            }
        };

        RunState state = freshState();
        state.setPhase(Phase.CONTINUING);
        state.setUserFeedback("The race screen is unreadable on a phone.");
        state.setBaselineCommit(new Workspace(paths, "house rules").scaffold(spec));

        RunState finished = advance(spec, state, agentsWhere(recorder,
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW)));

        assertEquals(1, briefs.size(), "expected a single turn");
        assertTrue(briefs.get(0).contains("unreadable on a phone"), "the feedback never arrived");
        assertNull(finished.getUserFeedback(), "it must not be delivered a second time");
        assertEquals(Phase.DONE, finished.getPhase());
    }

    @Test
    @DisplayName("a project waiting for approval is not the same as one you accepted")
    void doneAndAcceptedAreDistinct() {
        assertTrue(Phase.DONE.terminal());
        assertTrue(Phase.ACCEPTED.terminal());
        assertEquals("waiting for your approval", Progress.label(Phase.DONE));
        assertEquals("you accepted it", Progress.label(Phase.ACCEPTED));
    }

    @Test
    @DisplayName("state is written after every transition, not only at the end")
    void persistsProgress() throws IOException {
        Spec spec = spec("exit 0", 2);
        Agents agents = agentsWhere(
                new StubAgent(AgentKind.CLAUDE, writes("Main.java", "x"), finished()),
                new StubAgent(AgentKind.CODEX, OrchestratorTest::writesNothing, CLEAN_REVIEW));

        advance(spec, freshState(), agents);

        RunState persisted = RunStore.load(paths.stateFile()).orElseThrow();
        assertEquals(Phase.DONE, persisted.getPhase());
        assertNotNull(persisted.getBaselineCommit());
        assertNotNull(persisted.getUpdatedAt());
    }
}
