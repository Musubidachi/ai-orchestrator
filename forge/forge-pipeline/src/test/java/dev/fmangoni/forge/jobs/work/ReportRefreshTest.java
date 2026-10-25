package dev.fmangoni.forge.jobs.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportRefreshTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a project whose repository is not here has no report written: nothing is run, nothing is left behind")
    void noReportForAProjectNotHere() throws Exception {
        // Shelving four families each ended with "could not write the report": the refresh after the
        // shelf job ran git diff in a repository that had just gone, and left logs/git.log in the
        // shelf's place. Its report is on the shelf, and comes back with it.
        RunPaths paths = RunPaths.under(root.resolve("runs"), "004");
        RunState state = new RunState();
        state.setId("004");
        state.setPhase(Phase.ACCEPTED);
        state.setBaselineCommit("9e45e333393ee0745b737209384f53798bbbc0af");
        RunStore.save(paths.stateFile(), state);
        Files.writeString(paths.root().resolve("shelved.json"), "{}");

        ReportRefresh.write(paths, null);

        try (Stream<Path> left = Files.list(paths.root())) {
            assertEquals(Set.of("state.json", "shelved.json"),
                    left.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }
}
