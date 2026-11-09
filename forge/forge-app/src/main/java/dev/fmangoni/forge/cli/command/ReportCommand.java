package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.cli.support.EveryReport;
import dev.fmangoni.forge.cli.support.RunStates;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.report.IndexReport;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * {@code forge report}: writes every project's report and the index again from state on disk,
 * leaving alone a project whose repository is not on this machine.
 */
@UtilityClass
public class ReportCommand {

    public void run(ForgeHome home) {
        List<RunState> states = RunStates.all(home);
        EveryReport.write(home, states)
                .forEach((id, why) -> System.out.println("Left " + id + "'s report as it is: " + why));
        new IndexReport(home.root(), states).write();
        System.out.println("Report: " + home.root().resolve("index.html"));
    }
}
