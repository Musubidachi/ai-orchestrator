package dev.fmangoni.forge.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.report.section.VerdictSection;
import dev.fmangoni.forge.state.RunState;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The report's verdict after a quick gate says what that gate covered, never "all". */
class QuickGateVerdictTest {

    private static final String SUITE = "npx playwright test";

    private static RunState after(List<String> notRun, CommandResult failure) {
        return RunState.builder()
                .id("001")
                .title("Test project")
                .assignedTo(AgentKind.CLAUDE)
                .phase(Phase.CONTINUING)
                .notRunAtLastGate(notRun)
                .lastFailure(failure)
                .lastError(failure == null ? null : "1 acceptance command failed")
                .build();
    }

    @Test
    @DisplayName("a quick gate that passed names the commands it left for the full gate")
    void quickPass() {
        String page = VerdictSection.render(after(List.of(SUITE), null));

        assertTrue(page.contains("The quicker acceptance commands passed."), page);
        assertTrue(page.contains(SUITE), page);
        assertFalse(page.contains("All acceptance commands passed."), page);
    }

    @Test
    @DisplayName("a full gate that passed still says all of them did")
    void fullPass() {
        String page = VerdictSection.render(after(List.of(), null));

        assertTrue(page.contains("All acceptance commands passed."), page);
        assertFalse(page.contains("quicker"), page);
    }

    @Test
    @DisplayName("a quick gate that failed reads as any failure: the full gate would have stopped there too")
    void quickFailure() {
        CommandResult failure = new CommandResult("mvn -q verify", 1, "BUILD FAILURE", false,
                Duration.ofSeconds(40), "gate-1.bat");

        String page = VerdictSection.render(after(List.of(SUITE), failure));

        assertTrue(page.contains("Not accepted."), page);
        assertTrue(page.contains("BUILD FAILURE"), page);
        assertFalse(page.contains("passed."), page);
    }
}
