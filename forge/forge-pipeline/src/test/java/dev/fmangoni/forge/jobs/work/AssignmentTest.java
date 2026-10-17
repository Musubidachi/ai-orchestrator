package dev.fmangoni.forge.jobs.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.agent.policy.model.VendorSettings;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which vendor gets a project that did not name one.
 *
 * <p>Small enough to look obviously correct, which is why it had no tests. The two things it can
 * get wrong are both invisible until quota is already spent: overriding an explicit {@code assign:}
 * in someone's spec, and handing new work to a vendor that cannot currently run it.
 */
class AssignmentTest {

    @TempDir
    Path dir;

    private Spec spec(String id, String assign) throws IOException {
        Path file = dir.resolve(id + "-thing.md");
        Files.writeString(file, """
                ---
                id: "%s"
                title: Thing %s
                assign: %s
                acceptance:
                  - echo ok
                ---

                ## Goal
                Thing.
                """.formatted(id, id, assign), StandardCharsets.UTF_8);
        return SpecLoader.load(file);
    }

    private RunState assignedRun(AgentKind kind, Phase phase) {
        RunState state = new RunState();
        state.setAssignedTo(kind);
        state.setPhase(phase);
        return state;
    }

    private QuotaLedger ledgerWith(AgentKind... exhausted) {
        QuotaLedger ledger = new QuotaLedger(dir.resolve("quota-" + exhausted.length + ".json"));
        for (AgentKind kind : exhausted) {
            ledger.record(kind, Instant.now().plus(2, ChronoUnit.HOURS), "limit");
        }
        return ledger;
    }

    @Test
    @DisplayName("an explicit assignment in the spec is never overridden")
    void explicitAssignmentWins() throws IOException {
        // Even with Codex idle and Claude carrying everything, the spec's choice stands.
        Assignment assignment = new Assignment(List.of(
                assignedRun(AgentKind.CLAUDE, Phase.CONTINUING),
                assignedRun(AgentKind.CLAUDE, Phase.CONTINUING)));

        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "claude")));
        assertEquals(AgentKind.CODEX, assignment.decide(spec("002", "codex")));
    }

    @Test
    @DisplayName("an explicit assignment stands even when that vendor is out of quota")
    void explicitAssignmentSurvivesExhaustion() throws IOException {
        // It waits rather than being silently rebuilt by the other model, which would hand a
        // project to a vendor its author deliberately did not choose.
        Assignment assignment = new Assignment(List.of(), ledgerWith(AgentKind.CLAUDE));

        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "claude")));
    }

    @Test
    @DisplayName("auto spreads work rather than alternating blindly")
    void autoBalancesAcrossVendors() throws IOException {
        Assignment assignment = new Assignment(List.of());

        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "auto")));
        assertEquals(AgentKind.CODEX, assignment.decide(spec("002", "auto")));
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("003", "auto")));
    }

    @Test
    @DisplayName("work still owed from previous runs counts towards the balance")
    void countsExistingBacklog() throws IOException {
        // Two half-finished Claude projects from last night: the next auto project goes to Codex
        // rather than piling onto the vendor already carrying a backlog.
        Assignment assignment = new Assignment(List.of(
                assignedRun(AgentKind.CLAUDE, Phase.CONTINUING),
                assignedRun(AgentKind.CLAUDE, Phase.REPAIRING)));

        assertEquals(AgentKind.CODEX, assignment.decide(spec("001", "auto")));
    }

    @Test
    @DisplayName("finished projects do not count as load")
    void finishedWorkIsNotBacklog() throws IOException {
        Assignment assignment = new Assignment(List.of(
                assignedRun(AgentKind.CLAUDE, Phase.ACCEPTED),
                assignedRun(AgentKind.CLAUDE, Phase.DONE),
                assignedRun(AgentKind.CLAUDE, Phase.PARKED)));

        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "auto")),
                "work that is over is not work outstanding");
    }

    @Test
    @DisplayName("an exhausted vendor is skipped, however even the workload looks")
    void avoidsExhaustedVendor() throws IOException {
        Assignment assignment = new Assignment(List.of(), ledgerWith(AgentKind.CLAUDE));

        // Balance alone would say Claude; availability beats balance, because assigning to a
        // vendor that cannot start buys a project that sits still.
        assertEquals(AgentKind.CODEX, assignment.decide(spec("001", "auto")));
    }

    @Test
    @DisplayName("when both vendors are out, balance decides again")
    void bothExhaustedFallsBackToBalance() throws IOException {
        Assignment assignment =
                new Assignment(List.of(), ledgerWith(AgentKind.CLAUDE, AgentKind.CODEX));

        // Neither can start now, so the assignment is simply recorded for whenever one returns.
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "auto")));
    }

    private static VendorPolicy policy(boolean claudeBuilds, boolean codexBuilds) {
        Map<String, VendorSettings> map = new LinkedHashMap<>();
        map.put("CLAUDE", new VendorSettings(claudeBuilds, 1));
        map.put("CODEX", new VendorSettings(codexBuilds, 1));
        return new VendorPolicy(map);
    }

    @Test
    @DisplayName("a vendor excused from building gets no new projects")
    void excusedVendorIsSkipped() throws IOException {
        Assignment assignment = new Assignment(List.of(), null,
                policy(true, false));

        // Balance alone would alternate; the setting says otherwise, every time.
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "auto")));
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("002", "auto")));
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("003", "auto")));
    }

    @Test
    @DisplayName("it governs auto only — an explicit assignment still wins")
    void explicitAssignmentIsUntouched() throws IOException {
        Assignment assignment = new Assignment(List.of(), null,
                policy(true, false));

        assertEquals(AgentKind.CODEX, assignment.decide(spec("001", "codex")),
                "a spec that names a vendor is a decision, not a preference");
    }

    @Test
    @DisplayName("excusing both vendors is ignored rather than stopping the queue")
    void excusingEveryoneIsIgnored() throws IOException {
        Assignment assignment = new Assignment(List.of(), null,
                policy(false, false));

        // Falls back to balancing. A setting that left nothing able to build would be a worse
        // outcome than the imbalance it was meant to correct.
        assertEquals(AgentKind.CLAUDE, assignment.decide(spec("001", "auto")));
        assertEquals(AgentKind.CODEX, assignment.decide(spec("002", "auto")));
    }
}
