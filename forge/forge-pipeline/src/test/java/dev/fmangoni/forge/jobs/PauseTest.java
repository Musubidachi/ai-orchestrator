package dev.fmangoni.forge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.git.RepoLock;
import dev.fmangoni.forge.jobs.pause.PauseNote;
import dev.fmangoni.forge.jobs.pause.Pausing;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.status.JobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stop with a reason ({@code jobs.pause}), through the facade the dashboard calls.
 *
 * <p>A plain Stop let the queue start the project again as soon as its slot was free. Each test here
 * is about what a pause holds back, what lets it go, and what it refuses.
 */
class PauseTest {

    @TempDir
    Path root;

    private ForgeHome home;
    private QuotaLedger quota;
    private JobRunner jobs;

    /** Never invoked: these tests stop before an agent would be asked to do anything, but one. */
    private record UnusedAgent(AgentKind kind) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            throw new AssertionError("no test in this class should reach a vendor");
        }
    }

    /** An agent that holds its turn open until released or interrupted, so a project stays running. */
    private static final class HeldAgent implements Agent {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentKind kind() {
            return AgentKind.CLAUDE;
        }

        @Override
        public AgentResult run(AgentTask task) {
            calls.incrementAndGet();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("held turn released");
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.createDirectories(home.runs());
        Files.writeString(home.houseRulesFile(), "# House rules\n");
        quota = new QuotaLedger(root.resolve("quota.json"));
        jobs = runnerWith(new UnusedAgent(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("a paused project is held, with its reason, and the queue leaves it out")
    void pausedProjectIsHeld() {
        spec("001", "Alpha", 1);
        spec("002", "Beta", 2);
        runIn("001", Phase.SCAFFOLDED);
        runIn("002", Phase.SCAFFOLDED);

        String result = jobs.pause("001", "  Waiting for art  ");

        assertEquals("paused; the queue leaves it alone until you press Resume Now", result);
        assertEquals(Pausing.UNTIL_RESUMED, stateOf("001").getCoolOffUntil());
        PauseNote note = jobs.pauses().get("001");
        assertEquals("Waiting for art", note.reason());
        assertTrue(note.at().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)), String.valueOf(note.at()));
        assertEquals(1, jobs.pauses().size(), "only the paused project has a note: " + jobs.pauses());
        // The queue numbers only what it could start now: the other project moves up to first.
        assertEquals(0, statusOf("001").progress().getQueuePosition());
        assertEquals(1, statusOf("002").progress().getQueuePosition());
    }

    @Test
    @DisplayName("Resume Now lets it go, even when the start itself has to wait")
    void resumeLiftsThePause() {
        spec("001", "Alpha", 1);
        runIn("001", Phase.SCAFFOLDED);
        jobs.pause("001", "Saving quota");
        // Out of quota, so the start is refused; the pause must not outlive the click regardless.
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");

        String result = jobs.start("001");

        assertNotEquals("started", result);
        assertNull(stateOf("001").getCoolOffUntil(), "still held after Resume Now: " + result);
        assertEquals(Map.of(), jobs.pauses());
        assertFalse(Files.exists(paths("001").root().resolve("pause.json")), "the note outlived its pause");
    }

    @Test
    @DisplayName("pausing a held project again changes only the reason, not when it was paused")
    void changingTheReasonKeepsTheTime() throws InterruptedException {
        spec("001", "Alpha", 1);
        runIn("001", Phase.SCAFFOLDED);
        jobs.pause("001", "Waiting for art");
        Instant first = jobs.pauses().get("001").at();
        Thread.sleep(30);

        String result = jobs.pause("001", "Rewriting the spec");

        assertEquals("reason saved", result);
        assertEquals(new PauseNote("Rewriting the spec", first), jobs.pauses().get("001"));
        assertEquals(Pausing.UNTIL_RESUMED, stateOf("001").getCoolOffUntil());
    }

    @Test
    @DisplayName("a finished project, one never started, and an unknown id are not paused")
    void refusesWhatCannotBeHeld() {
        spec("001", "Alpha", 1);
        spec("002", "Beta", 2);
        runIn("001", Phase.DONE);

        assertEquals("already DONE", jobs.pause("001", ""));
        assertEquals("it has not started, so there is nothing to pause", jobs.pause("002", ""));
        assertEquals("no spec with id 999", jobs.pause("999", ""));
        assertEquals(Map.of(), jobs.pauses());
        assertFalse(Files.exists(paths("002").stateFile()), "refusing wrote a state for a project never begun");
    }

    @Test
    @DisplayName("a pause waits its turn behind another Forge process in the repository")
    void anotherProcessInTheRepository() {
        spec("001", "Alpha", 1);
        runIn("001", Phase.SCAFFOLDED);

        try (RepoLock other = RepoLock.tryAcquire(paths("001")).orElseThrow()) {
            assertEquals("another Forge process is working in this repository — try again when it stops",
                    jobs.pause("001", "Waiting for art"));
        }
        assertNull(stateOf("001").getCoolOffUntil(), "the state belongs to whoever holds the lock");
    }

    @Test
    @DisplayName("a long reason is cut to a line's worth")
    void longReasonsAreCut() {
        spec("001", "Alpha", 1);
        runIn("001", Phase.SCAFFOLDED);

        jobs.pause("001", "x".repeat(800));

        String reason = jobs.pauses().get("001").reason();
        assertEquals(501, reason.length());
        assertTrue(reason.endsWith("…"), reason);
    }

    @Test
    @DisplayName("a running project is stopped and held, so the slot it frees does not go back to it")
    void stopsAndHoldsARunningProject() throws Exception {
        // With the queue on, the plain Stop this replaces freed the slot and the fill that followed
        // started the same project again, before anyone could say why it was stopped.
        HeldAgent held = new HeldAgent();
        JobRunner runner = runnerWith(held);
        spec("001", "Alpha", 1);
        assertEquals("started", runner.start("001"));
        for (int i = 0; i < 200 && held.calls.get() == 0; i++) {
            Thread.sleep(25);
        }
        runner.setAutoRun(true);

        String result = runner.pause("001", "Trying it myself");
        awaitIdle(runner);

        try {
            assertEquals("stopped; the queue leaves it alone until you press Resume Now", result);
            assertFalse(runner.isRunning("001"), "the queue started it again");
            assertEquals(1, held.calls.get(), "the queue started it again");
            assertEquals(Pausing.UNTIL_RESUMED, stateOf("001").getCoolOffUntil());
            assertEquals("Trying it myself", runner.pauses().get("001").reason());
        } finally {
            runner.setAutoRun(false);
            held.release.countDown();
            awaitIdle(runner);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private JobRunner runnerWith(Agent claude) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, claude);
        map.put(AgentKind.CODEX, new UnusedAgent(AgentKind.CODEX));
        return new JobRunner(home, new Agents(map), 4, quota);
    }

    /** A spec on the wishlist, with a plan already on disk so a start goes straight to building. */
    private void spec(String id, String title, int priority) {
        String front = """
                ---
                id: "%s"
                title: %s
                priority: %d
                assign: claude
                acceptance:
                  - echo ok
                ---

                ## Goal
                %s.
                """.formatted(id, title, priority, title);
        try {
            Files.writeString(home.wishlist().resolve(id + "-" + title.toLowerCase() + ".md"), front,
                    StandardCharsets.UTF_8);
            Files.createDirectories(paths(id).root());
            Files.writeString(paths(id).planFile(), "## Approach\nBuild it.\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A run on disk in a given phase, with the repository a project that has begun has. */
    private void runIn(String id, Phase phase) {
        RunState state = new RunState();
        state.setId(id);
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        RunStore.save(paths(id).stateFile(), state);
        try {
            Files.createDirectories(paths(id).repo().resolve(".git"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RunPaths paths(String id) {
        return RunPaths.under(home.runs(), id);
    }

    private RunState stateOf(String id) {
        return RunStore.load(paths(id).stateFile()).orElseThrow();
    }

    private JobStatus statusOf(String id) {
        Optional<JobStatus> row = jobs.statuses().stream()
                .filter(s -> s.identity().getId().equals(id)).findFirst();
        return row.orElseThrow(() -> new AssertionError("no status row for " + id));
    }

    /** Waits for every job's thread, so none outlives the test's directory. */
    private static void awaitIdle(JobRunner runner) throws InterruptedException {
        for (int i = 0; i < 400 && !runner.settled(); i++) {
            Thread.sleep(25);
        }
    }
}
