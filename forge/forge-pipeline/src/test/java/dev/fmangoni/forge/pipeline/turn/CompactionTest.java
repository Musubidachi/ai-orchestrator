package dev.fmangoni.forge.pipeline.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.state.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * When it is safe to forget a conversation.
 *
 * <p>The cost of getting this wrong is asymmetric and worth stating. Compacting too late wastes
 * quota, which is annoying. Compacting too early throws away context the next turn needed, and the
 * symptom is not an error — it is an agent quietly redoing work or re-entering a dead end it had
 * already abandoned, which looks like the model being bad at its job.
 *
 * <p>So every test here is about a reason <em>not</em> to compact.
 */
class CompactionTest {

    private static final int EVERY_THREE = 3;

    private RunState state(Phase phase, int sessionsOnConversation, String digest) {
        RunState state = new RunState();
        state.setPhase(phase);
        state.setSessionsOnConversation(sessionsOnConversation);

        Plan plan = new Plan();
        plan.setSummary("did some work");
        plan.setDigest(digest);
        state.setPlan(plan);
        return state;
    }

    @Test
    @DisplayName("compacts at a clean boundary once the conversation has run long enough")
    void compactsWhenDue() {
        assertTrue(Compaction.due(state(Phase.CONTINUING, 3, "how this is built"), EVERY_THREE));
    }

    @Test
    @DisplayName("a short conversation is left alone")
    void waitsForTheThreshold() {
        assertFalse(Compaction.due(state(Phase.CONTINUING, 2, "how this is built"), EVERY_THREE));
    }

    @Test
    @DisplayName("without a digest it never compacts, however long the conversation is")
    void neverCompactsWithoutADigest() {
        // A fresh session with no briefing is worse than an expensive one that knows what it is
        // doing, so the absence of notes is a veto rather than a detail.
        assertFalse(Compaction.due(state(Phase.CONTINUING, 50, ""), EVERY_THREE));
        assertFalse(Compaction.due(state(Phase.CONTINUING, 50, null), EVERY_THREE));
        assertFalse(Compaction.due(state(Phase.CONTINUING, 50, "   "), EVERY_THREE));
    }

    @Test
    @DisplayName("an unusable handoff is not a boundary to compact at")
    void neverCompactsWithoutAUsablePlan() {
        RunState state = state(Phase.CONTINUING, 10, "notes");
        state.setPlan(Plan.failed("could not parse the reply"));

        assertFalse(Compaction.due(state, EVERY_THREE));
    }

    @Test
    @DisplayName("a project that has never produced a handoff is not compacted")
    void neverCompactsWithNoPlanAtAll() {
        RunState state = state(Phase.CONTINUING, 10, "notes");
        state.setPlan(null);

        assertFalse(Compaction.due(state, EVERY_THREE));
    }

    @Test
    @DisplayName("a project in the middle of a repair keeps the conversation holding the failure")
    void neverCompactsMidRepair() {
        // The context being dropped would be the context of the failure being chased, which is
        // precisely what that turn needs.
        assertFalse(Compaction.due(state(Phase.REPAIRING, 9, "notes"), EVERY_THREE));
        assertFalse(Compaction.due(state(Phase.REVIEW_REPAIR, 9, "notes"), EVERY_THREE));
    }

    @Test
    @DisplayName("a suspended or parked project is not compacted while it waits")
    void neverCompactsWhileStopped() {
        assertFalse(Compaction.due(state(Phase.SUSPENDED, 9, "notes"), EVERY_THREE));
        assertFalse(Compaction.due(state(Phase.PARKED, 9, "notes"), EVERY_THREE));
    }

    @Test
    @DisplayName("zero turns it off entirely")
    void zeroDisablesIt() {
        assertFalse(Compaction.due(state(Phase.CONTINUING, 100, "notes"), 0));
        assertFalse(Compaction.due(state(Phase.CONTINUING, 100, "notes"), -1));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Phase.class)
    @DisplayName("every phase has a decided answer, and none of them throws")
    void everyPhaseIsDecided(Phase phase) {
        // The same guard as JobRunnerTest: adding a phase must not silently inherit someone
        // else's answer about whether a conversation is safe to discard.
        Compaction.due(state(phase, 10, "notes"), EVERY_THREE);
    }
}
