package dev.fmangoni.forge.walkthrough;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.plan.PlanStep;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a project sounds like.
 *
 * <p>Two things matter here and neither is wording. The first is that a listener cannot skim: the
 * verdict has to arrive before the detail, and a state the report would show in red must not be
 * spoken as though everything were fine. The second is that nothing markup-shaped reaches the
 * synthesiser, which reads asterisks and backticks aloud.
 */
class WalkthroughTest {

    private RunState state(Phase phase) {
        RunState state = new RunState();
        state.setId("003");
        state.setTitle("Invoice Batch Importer");
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setSessions(4);
        state.setRepairRounds(1);
        state.setWorkedMillis(Duration.ofMinutes(140).toMillis());
        state.setPlan(plan());
        return state;
    }

    private Plan plan() {
        Plan plan = new Plan();
        plan.setStatus("complete");
        plan.setSummary("Built the importer, the report and the summary screen.");
        plan.setOverview("src/main/java/RaceLoop.java");
        plan.setFeatures(new ArrayList<>(List.of(
                new PlanFeature("Report export", "open the report"),
                new PlanFeature("Monthly summary", "win a title"))));
        plan.setNotVerified(new ArrayList<>(List.of("nothing renders the replay and looks at it")));
        return plan;
    }

    private Review clean() {
        Review review = new Review();
        review.setFindings(new ArrayList<>());
        return review;
    }

    @Test
    @DisplayName("it opens with what the project is and how it went, before any detail")
    void verdictComesFirst() {
        String spoken = Walkthrough.of(state(Phase.DONE), clean(), null);

        assertTrue(spoken.startsWith("Invoice Batch Importer, built by CLAUDE."), spoken);
        int verdict = spoken.indexOf("waiting for you to look at it");
        int detail = spoken.indexOf("Report export");
        assertTrue(verdict > 0 && verdict < detail,
                "a listener cannot skim back up: the verdict has to come first\n" + spoken);
    }

    @Test
    @DisplayName("nothing markup-shaped reaches the synthesiser")
    void nothingToReadOutAsPunctuation() {
        RunState state = state(Phase.DONE);
        state.getPlan().setSummary("Uses `RaceLoop` and **markdown** from the model.");

        String spoken = Walkthrough.of(state, clean(), null);

        assertFalse(spoken.contains("`") || spoken.contains("*") || spoken.contains("#"),
                "a synthesiser reads these aloud: " + spoken);
    }

    @Test
    @DisplayName("a parked project is not described as though it were finished")
    void aParkedProjectSaysSo() {
        RunState state = state(Phase.PARKED);
        state.setLastError("acceptance still failing after 3 repair rounds");
        state.setLastFailure(new CommandResult("npm run test:e2e --reporter=line", 1, "boom", false,
                Duration.ofSeconds(30), "script.cmd"));

        String spoken = Walkthrough.of(state, clean(), null);

        assertTrue(spoken.contains("parked, and needs you"), spoken);
        assertTrue(spoken.contains("acceptance still failing after 3 repair rounds"));
        assertTrue(spoken.contains("npm run test:e2e"), "it should say which command, briefly: " + spoken);
        assertFalse(spoken.contains("--reporter=line"), "the flags are noise out loud: " + spoken);
    }

    @Test
    @DisplayName("an unreviewed project is never spoken as though someone had read it")
    void unreviewedIsSaidPlainly() {
        assertTrue(Walkthrough.of(state(Phase.DONE), null, null)
                .contains("No other model has read this yet"));
        assertTrue(Walkthrough.of(state(Phase.DONE), Review.unavailable("RATE_LIMITED", null), null)
                .contains("No other model has read this yet"));

        Review unreadable = Review.failed("no JSON in the reply");
        assertTrue(Walkthrough.of(state(Phase.DONE), unreadable, null).contains("counts as unreviewed"));
    }

    @Test
    @DisplayName("blocking findings are counted and the first one is read out")
    void findingsAreSpoken() {
        Review review = new Review();
        Finding blocking = new Finding();
        blocking.setSeverity("blocking");
        blocking.setSummary("the summary never totals the last row");
        review.setFindings(new ArrayList<>(List.of(blocking)));

        String spoken = Walkthrough.of(state(Phase.DONE), review, null);

        assertTrue(spoken.contains("1 blocking finding"), spoken);
        assertTrue(spoken.contains("the summary never totals the last row"));
    }

    @Test
    @DisplayName("a project with no handoff still gets a walkthrough, and says what is missing")
    void aRunWithNothingRecorded() {
        RunState bare = new RunState();
        bare.setId("001");
        bare.setTitle("Half a project");
        bare.setPhase(Phase.GATING);

        String spoken = Walkthrough.of(bare, null, null);

        assertTrue(spoken.contains("Half a project."), spoken);
        assertTrue(spoken.contains("still being built"));
        assertTrue(spoken.contains("no account of what it built"));
        assertTrue(spoken.contains("named no class as its top-level flow"));
        assertTrue(spoken.contains("security check has not been run"));
    }

    @Test
    @DisplayName("work left over is read out, so it can be heard without opening the report")
    void remainingWorkIsSpoken() {
        RunState state = state(Phase.DONE);
        PlanStep next = new PlanStep();
        next.setStep("film the replay and attach it as evidence");
        state.getPlan().setRemaining(new ArrayList<>(List.of(next)));

        String spoken = Walkthrough.of(state, clean(), null);

        assertTrue(spoken.contains("1 piece of work is left"), spoken);
        assertTrue(spoken.contains("film the replay"));
    }

    @Test
    @DisplayName("names, durations and lists are said the way a person would say them")
    void spokenForms() {
        assertEquals("Race Loop", Spoken.typeName("src/main/java/RaceLoop.java"));
        assertEquals("Job Runner", Spoken.typeName("JobRunner"));
        assertEquals("HTTP Server", Spoken.words("HTTPServer"));
        assertEquals("2 hours and 20 minutes", Spoken.duration(140 * 60));
        assertEquals("1 hour", Spoken.duration(60 * 60));
        assertEquals("45 seconds", Spoken.duration(45));
        assertEquals("3 minutes", Spoken.duration(200));
        assertEquals("1 finding", Spoken.count(1, "finding"));
        assertEquals("2 findings", Spoken.count(2, "finding"));
        assertEquals("3 entries", Spoken.count(3, "entry"));
        assertEquals("a, b and c", Spoken.and(List.of("a", "b", "c")));
        assertEquals("a", Spoken.and(List.of("a", "", "  ")));
        assertEquals("npm run test:e2e", Spoken.command("npm run test:e2e --reporter=line"));
        assertEquals("a command", Spoken.command("  "));
    }
}
