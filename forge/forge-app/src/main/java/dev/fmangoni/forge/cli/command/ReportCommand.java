package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.cli.support.RunStates;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.IndexReport;
import dev.fmangoni.forge.report.ProjectReport;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/** {@code forge report}: regenerates every project's report and the index from state on disk. */
@UtilityClass
public class ReportCommand {

    public void run(ForgeHome home) {
        List<RunState> states = RunStates.all(home);
        for (RunState state : states) {
            RunPaths paths = RunPaths.under(home.runs(), state.getId());
            new ProjectReport(paths, state, ReviewStore.load(paths.reviewFile())).write();
        }
        new IndexReport(home.root(), states).write();
        System.out.println("Report: " + home.root().resolve("index.html"));
    }
}
