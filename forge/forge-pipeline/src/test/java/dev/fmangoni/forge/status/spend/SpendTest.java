package dev.fmangoni.forge.status.spend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine's token total, read off the runs on disk.
 *
 * <p>What is pinned here is that the total and the split cannot drift apart. Each project's own
 * total is the authority — it is the figure its card shows — so the per-model shares have to add up
 * to it, and whatever they do not cover has to be reported as uncovered rather than quietly
 * dropped or spread about. A page that showed a grand total the rows disagreed with would be worse
 * than one that showed no rows.
 */
class SpendTest {

    @TempDir
    Path runs;

    private void project(String id, long tokens, Map<String, Long> byModel) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle("Project " + id);
        state.setTokensSpent(tokens);
        state.setTokensByModel(new LinkedHashMap<>(byModel));
        RunStore.save(RunPaths.under(runs, id).stateFile(), state);
    }

    @Test
    @DisplayName("every project's tokens are added up, whatever phase it is in")
    void addsUpEveryProject() {
        project("001", 300, Map.of("CLAUDE/sonnet", 300L));
        project("002", 700, Map.of("CODEX/gpt-5.6-sol", 700L));

        Spending spending = Spend.of(runs);

        assertEquals(1000, spending.getTotal());
        assertEquals(2, spending.getProjects());
        assertEquals(0, spending.getUnattributed());
    }

    @Test
    @DisplayName("one model's share is summed across the projects it worked on")
    void sumsOneModelAcrossProjects() {
        project("001", 500, Map.of("CLAUDE/sonnet", 200L, "CLAUDE/opus", 300L));
        project("002", 400, Map.of("CLAUDE/sonnet", 400L));

        Spending spending = Spend.of(runs);

        ModelSpend top = spending.getByModel().getFirst();
        assertEquals("sonnet", top.getModel());
        assertEquals("CLAUDE", top.getVendor());
        assertEquals(600, top.getTokens());
        // Two projects for sonnet, one for opus: the count is projects, not turns.
        assertEquals(2, top.getProjects());
        assertEquals(1, spending.getByModel().getLast().getProjects());
    }

    @Test
    @DisplayName("tokens spent before the split was recorded are reported, not absorbed")
    void reportsWhatPredatesTheSplit() {
        // What every project on this machine looks like: a long history with no split, and one
        // turn since. Attributing the history to that turn's model would have been wrong by 99%.
        project("001", 1_000_000, Map.of("CLAUDE/sonnet", 10_000L));

        Spending spending = Spend.of(runs);

        assertEquals(1_000_000, spending.getTotal());
        assertEquals(10_000, spending.getAttributed());
        assertEquals(990_000, spending.getUnattributed());
        assertEquals(1_000_000, spending.getAttributed() + spending.getUnattributed());
    }

    @Test
    @DisplayName("a project that has spent nothing is not counted as a project")
    void ignoresProjectsThatHaveSpentNothing() {
        project("001", 0, Map.of());
        project("002", 50, Map.of("CLAUDE/haiku", 50L));

        Spending spending = Spend.of(runs);

        assertEquals(1, spending.getProjects());
        assertEquals(50, spending.getTotal());
    }

    @Test
    @DisplayName("a runs directory that is not there answers zero rather than failing")
    void survivesAMissingRunsDirectory() {
        Spending spending = Spend.of(runs.resolve("nothing-here"));

        assertEquals(0, spending.getTotal());
        assertEquals(0, spending.getProjects());
        assertTrue(spending.getByModel().isEmpty());
    }

    @Test
    @DisplayName("a state written before the split existed reads as unattributed, not as a failure")
    void toleratesAStateWithNoSplit() {
        RunState state = new RunState();
        state.setId("001");
        state.setTokensSpent(1234);
        state.setTokensByModel(null);
        RunStore.save(RunPaths.under(runs, "001").stateFile(), state);

        Spending spending = Spend.of(runs);

        assertEquals(1234, spending.getTotal());
        assertEquals(1234, spending.getUnattributed());
        assertTrue(spending.getByModel().isEmpty());
    }
}
