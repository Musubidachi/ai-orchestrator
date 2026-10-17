package dev.fmangoni.forge.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.status.model.StatusIdentity;
import dev.fmangoni.forge.state.RunState;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the dashboard is told about a project, and in particular what its clocks mean.
 *
 * <p>Written after a number on the card turned out to be measuring the wrong thing: "working for"
 * was wall clock since the project was created, and nothing froze it while the project sat
 * suspended on quota, so a job blocked at nine in the evening claimed to have been working all
 * night. The three clocks are separated here and pinned, because a plausible-looking wrong number
 * is worse than no number — it gets believed.
 *
 * <p>The null sweep guards the other hazard in this class. {@link JobStatus} has thirty-odd
 * components built by name; a component added later and not set by {@code queued} would default to
 * {@code null}, reach the browser as JSON {@code null}, and render as the word "null" in a stat.
 */
class JobStatusTest {

    @TempDir
    Path root;

    private RunPaths paths() {
        return RunPaths.under(root, "001");
    }

    private RunState state(Phase phase, Instant createdAt, Instant updatedAt, long workedMillis) {
        RunState state = new RunState();
        state.setId("001");
        state.setTitle("Test project");
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setCreatedAt(createdAt);
        state.setUpdatedAt(updatedAt);
        state.setWorkedMillis(workedMillis);
        return state;
    }

    private JobStatus statusOf(RunState state) {
        return JobStatus.of(state, paths(), 4, false, null, 1, 1, "", "");
    }

    // ------------------------------------------------------- the three clocks

    @Test
    @DisplayName("worked time comes from what was recorded, not from how long ago it started")
    void workedTimeIsAccumulatedNotDerived() {
        // The shape of the bug: created an hour ago, ninety seconds of actual work, and nothing
        // running now. Every honest reading of "how long has Forge worked on this" is 90 seconds.
        JobStatus status = statusOf(state(Phase.SUSPENDED,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800), 90_000));

        assertEquals(90, status.budget().getWorkedSeconds());
    }

    @Test
    @DisplayName("idle time is time since anything changed, which is what a stalled job needs")
    void idleTimeIsSinceTheLastChange() {
        JobStatus status = statusOf(state(Phase.SUSPENDED,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(1800), 30_000));

        assertTrue(status.budget().getIdleSeconds() >= 1795 && status.budget().getIdleSeconds() <= 1810,
                "expected about half an hour idle, got " + status.budget().getIdleSeconds());
    }

    @Test
    @DisplayName("a project sitting still accumulates idle time and no worked time")
    void sittingStillIsNotWork() {
        // Two polls of the same untouched state, which is precisely what the dashboard does every
        // four seconds. The number that grew before was the one labelled as effort.
        RunState state = state(Phase.SUSPENDED,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), 45_000);

        JobStatus first = statusOf(state);
        JobStatus second = statusOf(state);

        assertEquals(first.budget().getWorkedSeconds(), second.budget().getWorkedSeconds());
        assertTrue(second.budget().getIdleSeconds() >= first.budget().getIdleSeconds());
        assertEquals(45, second.budget().getWorkedSeconds());
    }

    @Test
    @DisplayName("total time freezes once a project is finished")
    void totalTimeStopsAtTheFinish() {
        JobStatus status = statusOf(state(Phase.DONE,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 600_000));

        // Created two hours ago, last touched an hour ago: the project took an hour of wall clock,
        // and the hour since is the dashboard being read, not the project running.
        assertTrue(status.budget().getTotalSeconds() >= 3595 && status.budget().getTotalSeconds() <= 3610,
                "expected an hour of wall clock, got " + status.budget().getTotalSeconds());
    }

    @Test
    @DisplayName("total time is still wall clock for a project that has not finished")
    void totalTimeRunsWhileUnfinished() {
        // Deliberately unchanged behaviour: this is the project's age, it is labelled as such on
        // the card, and for an unfinished project the answer really is "still going".
        JobStatus status = statusOf(state(Phase.GATING,
                Instant.now().minusSeconds(1800), Instant.now().minusSeconds(60), 120_000));

        assertTrue(status.budget().getTotalSeconds() >= 1795, "expected the age, got " + status.budget().getTotalSeconds());
    }

    @Test
    @DisplayName("sub-second steps are not rounded away")
    void subSecondWorkIsKept() {
        // Twenty steps of 300ms is six seconds of work. Accumulating in whole seconds would have
        // recorded none of it, and a project is dozens of steps.
        RunState state = state(Phase.GATING, Instant.now(), Instant.now(), 0);
        for (int i = 0; i < 20; i++) {
            state.setWorkedMillis(state.getWorkedMillis() + 300);
        }

        assertEquals(6, statusOf(state).budget().getWorkedSeconds());
    }

    @Test
    @DisplayName("the card is told what the last gate took, and which command has slowed down")
    void gateTimingsReachTheCard() {
        RunState state = state(Phase.DONE, Instant.now(), Instant.now(), 0);
        state.setCommandSeconds(new LinkedHashMap<>(Map.of(
                "npm test", List.of(20, 21, 19, 60),
                "npm run build", List.of(8, 9))));

        JobStatus status = statusOf(state);

        assertEquals(69, status.budget().getGateSeconds());
        assertEquals(List.of("npm test took 60s, usually 20s"),
                status.budget().getSlowerThanUsual());
    }

    // ------------------------------------------------------- the null sweep

    @Test
    @DisplayName("a queued project has no null component")
    void queuedIsFullyPopulated() throws Exception {
        assertNoNulls(JobStatus.queued("001", "Test project", 4, null, 1, 1, null));
    }

    @Test
    @DisplayName("a running project has no null component")
    void statusIsFullyPopulated() throws Exception {
        assertNoNulls(statusOf(state(Phase.GATING, Instant.now(), Instant.now(), 1000)));
    }

    /**
     * Every component readable and none of them null, inside every group.
     *
     * <p>By reflection rather than by assertion-per-field, so a component added later is covered
     * without anyone remembering to extend this — the same reason the phase sweep in
     * {@code JobRunnerTest} enumerates the enum instead of listing its constants. Recursive since
     * the record was grouped: the top level is six groups, and checking only those would pass with
     * every field inside them null.
     *
     * <p>It walks record components and Lombok getters alike. {@link JobStatus} is a record and its
     * groups are {@code @Value} classes — Lombok cannot build a record under Eclipse's compiler —
     * and a sweep that understood only records would have gone on passing while inspecting six
     * fields instead of fifty.
     */
    private void assertNoNulls(Object group) throws Exception {
        assertTrue(inspect(group) >= 50, "the sweep stopped finding fields; it is no longer checking anything");
    }

    private int inspect(Object group) throws Exception {
        int seen = 0;
        for (RecordComponent component : group.getClass().getRecordComponents() == null
                ? new RecordComponent[0] : group.getClass().getRecordComponents()) {
            seen += check(group, component.getName(), component.getAccessor().invoke(group));
        }
        for (Method getter : group.getClass().getMethods()) {
            boolean reads = getter.getParameterCount() == 0
                    && getter.getDeclaringClass() == group.getClass()
                    && (getter.getName().startsWith("get") || getter.getName().startsWith("is"));
            if (reads) {
                seen += check(group, getter.getName(), getter.invoke(group));
            }
        }
        return seen;
    }

    private int check(Object group, String name, Object value) throws Exception {
        assertNotNull(value, name + " is null, and would render as \"null\" on the card");
        boolean isGroup = value.getClass().getPackageName().equals(StatusIdentity.class.getPackageName());
        return 1 + (value.getClass().isRecord() || isGroup ? inspect(value) : 0);
    }

    @Test
    @DisplayName("the dashboard still receives one flat object, whatever the Java side groups")
    void wireFormatStaysFlat() throws Exception {
        // Json.mapper() is the mapper the editor's API serialises through (ui.Http delegates to it).
        var json = Json.mapper().readTree(Json.mapper()
                .writeValueAsString(statusOf(state(Phase.GATING, Instant.now(), Instant.now(), 1000))));

        for (String field : List.of("id", "phase", "queuePosition", "workedSeconds",
                "tokensSpent", "gateSeconds", "slowerThanUsual", "nextStep", "reviewState",
                "securityState", "securityForRuling")) {
            assertTrue(json.has(field), field + " is missing from the top level: " + json);
        }
        for (String group : List.of("identity", "progress", "budget", "handoff", "review", "outward")) {
            assertTrue(!json.has(group), "the page reads j." + "phase, not j.progress.phase: " + json);
        }
    }
}
