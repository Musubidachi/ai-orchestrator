package dev.fmangoni.forge.status.league;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Recheck;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The league table, read off runs on disk.
 *
 * <p>The points are an ordering and nothing else, so what is pinned here is that each one comes
 * from something that happened: a gate that passed, a person who accepted, a finding that was
 * fixed. A table that scored work nobody had judged would be a table of who was busiest.
 */
class LeagueTest {

    @TempDir
    Path runs;

    private void built(String id, AgentKind by, Phase phase, int repairRounds, int sessions, long tokens) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle("Project " + id);
        state.setAssignedTo(by);
        state.setPhase(phase);
        state.setRepairRounds(repairRounds);
        state.setSessions(sessions);
        state.setTokensSpent(tokens);
        RunStore.save(RunPaths.under(runs, id).stateFile(), state);
    }

    private void reviewed(String id, String reviewer, List<Finding> findings, List<Recheck> rechecks) {
        Review review = new Review();
        review.setReviewer(reviewer);
        review.setFindings(findings);
        review.setRechecks(rechecks);
        ReviewStore.save(RunPaths.under(runs, id).reviewFile(), review);
    }

    private static Finding blocking(String summary) {
        Finding finding = new Finding();
        finding.setSeverity("blocking");
        finding.setFile("Main.java");
        finding.setSummary(summary);
        return finding;
    }

    private Standing standingOf(String vendor) {
        return League.of(runs).stream().filter(s -> s.getVendor().equals(vendor)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a finished project scores, an accepted one scores more, and a clean gate adds one")
    void buildingPoints() {
        built("001", AgentKind.CLAUDE, Phase.DONE, 2, 9, 1_000);
        built("002", AgentKind.CLAUDE, Phase.ACCEPTED, 0, 5, 3_000);

        Standing claude = standingOf("CLAUDE");

        // 3 for finishing 001; 3 + 2 accepted + 1 clean gate for 002.
        assertEquals(9, claude.getPoints());
        assertEquals(9, claude.getBuildPoints());
        assertEquals(2, claude.getBuilt());
        assertEquals(2, claude.getFinished());
        assertEquals(1, claude.getAccepted());
        assertEquals(1, claude.getCleanGates());
        assertEquals(14, claude.getFinishedSessions());
        assertEquals(4_000, claude.getFinishedTokens());
    }

    @Test
    @DisplayName("a project that needed repairs is not a clean gate just because it was reopened and finished again")
    void reopeningDoesNotWipeTheRecord() {
        built("001", AgentKind.CLAUDE, Phase.DONE, 0, 9, 1_000);
        RunState state = RunStore.load(RunPaths.under(runs, "001").stateFile()).orElseThrow();
        // Three rounds the first time; reopening reset the budget, the record kept them.
        state.setRepairRoundsEver(3);
        RunStore.save(RunPaths.under(runs, "001").stateFile(), state);

        Standing claude = standingOf("CLAUDE");

        assertEquals(0, claude.getCleanGates());
        assertEquals(3, claude.getRepairRounds());
        assertEquals(3, claude.getPoints(), "finished, and nothing for a clean gate");
    }

    @Test
    @DisplayName("a parked project costs points, and one still being worked on scores nothing yet")
    void parkedAndUnjudged() {
        built("001", AgentKind.CODEX, Phase.PARKED, 4, 12, 9_000);
        built("002", AgentKind.CODEX, Phase.REPAIRING, 1, 3, 500);

        Standing codex = standingOf("CODEX");

        assertEquals(-2, codex.getPoints());
        assertEquals(2, codex.getBuilt());
        assertEquals(0, codex.getFinished());
        assertEquals(1, codex.getParked());
        assertEquals(5, codex.getRepairRounds(), "repair rounds are counted across everything it built");
        assertEquals(0, codex.getFinishedSessions(), "averages are over finished projects only");
    }

    @Test
    @DisplayName("a reviewer scores for findings that were fixed, loses for ones it withdrew, and nothing for open ones")
    void reviewingPoints() {
        built("001", AgentKind.CLAUDE, Phase.DONE, 0, 4, 100);
        reviewed("001", "CODEX (gpt-6-astra)",
                List.of(blocking("still open")),
                List.of(new Recheck("blocking-aaaaaaaa", "fixed", "gone"),
                        new Recheck("blocking-bbbbbbbb", "Fixed", "gone too"),
                        new Recheck("blocking-cccccccc", "withdrawn", "was wrong about this"),
                        new Recheck("blocking-dddddddd", "still_present", "not yet")));

        Standing codex = standingOf("CODEX");

        assertEquals(1, codex.getPoints(), "two fixed, one withdrawn");
        assertEquals(1, codex.getReviewPoints());
        assertEquals(0, codex.getBuilt(), "it reviewed this project; it did not build it");
        assertEquals(1, codex.getReviewed());
        assertEquals(2, codex.getFindingsFixed());
        assertEquals(1, codex.getFindingsWithdrawn());
        assertEquals(1, codex.getFindingsOpen());
    }

    @Test
    @DisplayName("a review that never ran, or could not be read, is nobody's review")
    void unusableReviewsAreNotCounted() {
        built("001", AgentKind.CLAUDE, Phase.DONE, 0, 4, 100);
        ReviewStore.save(RunPaths.under(runs, "001").reviewFile(), Review.unavailable("out of quota", null));
        built("002", AgentKind.CLAUDE, Phase.DONE, 0, 4, 100);
        reviewed("002", "someone new (model-x)", List.of(), List.of());

        assertEquals(List.of("CLAUDE"), League.of(runs).stream().map(Standing::getVendor).toList());
    }

    @Test
    @DisplayName("best first, and a tie goes to whoever finished more")
    void ordering() {
        built("001", AgentKind.CODEX, Phase.DONE, 1, 4, 100);             // 3
        built("002", AgentKind.CLAUDE, Phase.ACCEPTED, 0, 4, 100);        // 6
        built("003", AgentKind.ANTIGRAVITY, Phase.DONE, 0, 4, 100);       // 4
        built("004", AgentKind.ANTIGRAVITY, Phase.DONE, 1, 4, 100);       // 3
        built("005", AgentKind.ANTIGRAVITY, Phase.PARKED, 4, 4, 100);     // -2
        built("006", AgentKind.CODEX, Phase.DONE, 1, 4, 100);             // 3
        built("007", AgentKind.CODEX, Phase.PARKED, 4, 4, 100);           // -2
        built("008", AgentKind.CODEX, Phase.DONE, 0, 4, 100);             // 4

        List<Standing> table = League.of(runs);

        // CODEX 8 from three finished, CLAUDE 6, ANTIGRAVITY 5.
        assertEquals(List.of("CODEX", "CLAUDE", "ANTIGRAVITY"), table.stream().map(Standing::getVendor).toList());
        assertEquals(List.of(8, 6, 5), table.stream().map(Standing::getPoints).toList());
    }

    @Test
    @DisplayName("no runs, a stray file, or a folder with no state: an empty table and no error")
    void nothingToRead() throws IOException {
        assertEquals(List.of(), League.of(runs.resolve("absent")));

        Files.writeString(runs.resolve("notes.txt"), "not a run");
        Files.createDirectories(runs.resolve("009"));

        assertEquals(List.of(), League.of(runs));
    }
}
