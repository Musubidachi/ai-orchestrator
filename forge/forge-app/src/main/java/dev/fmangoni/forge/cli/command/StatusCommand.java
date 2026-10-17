package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.cli.support.RunStates;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/** {@code forge status}: one line per project. */
@UtilityClass
public class StatusCommand {

    public void run(ForgeHome home) {
        List<RunState> states = RunStates.all(home);
        if (states.isEmpty()) {
            System.out.println("No runs yet.");
            return;
        }
        System.out.printf("%-8s %-14s %-8s %s%n", "ID", "PHASE", "AGENT", "TITLE");
        for (RunState state : states) {
            System.out.printf("%-8s %-14s %-8s %s%n",
                    state.getId(), state.getPhase(),
                    state.getAssignedTo() == null ? "-" : state.getAssignedTo(),
                    state.getTitle());
        }
    }
}
