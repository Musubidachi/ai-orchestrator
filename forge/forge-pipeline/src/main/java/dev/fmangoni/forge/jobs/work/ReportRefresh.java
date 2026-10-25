package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.ProjectReport;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import lombok.experimental.UtilityClass;

/**
 * Rewrites a project's report after anything here changes it.
 *
 * <p>Reports used to be produced only by the {@code run} and {@code report} commands, so a project
 * built entirely from the dashboard finished with its report link pointing at a file that had never
 * been written; and a submission that leaves the page stale reads as a failure.
 */
@UtilityClass
public class ReportRefresh {

    /** Writes the report from the state on disk, or from {@code fallback} when there is none yet. */
    public void write(RunPaths paths, RunState fallback) {
        try {
            RunState state = RunStore.load(paths.stateFile()).orElse(fallback);
            if (state != null && notHere(paths, state)) {
                // Shelved, or gone: its report went with the rest of it, and is restored with it. Writing
                // one here ran git in a folder that is not there, and left a log behind in the shelf's place.
                return;
            }
            if (state != null) {
                new ProjectReport(paths, state, ReviewStore.load(paths.reviewFile())).write();
            }
        } catch (RuntimeException e) {
            // A missing report is a nuisance; losing the run or the submission over it would not be.
            System.err.println("could not write the report for " + paths.root() + ": " + e.getMessage());
        }
    }

    /** Begun, with no repository here: the same reading as {@link Eligibility#notHere}, from the paths alone. */
    private boolean notHere(RunPaths paths, RunState state) {
        return state.getPhase() != Phase.NEW && !Files.isDirectory(paths.repo().resolve(".git"));
    }
}
