package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.cli.support.Wiring;
import dev.fmangoni.forge.jobs.unattended.Scheduler;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/** {@code forge run}: advances every eligible project unattended, then writes the reports. */
@UtilityClass
public class RunCommand {

    public void run(ForgeHome home, String[] args) {
        List<Spec> specs = SpecLoader.loadAll(home.wishlist());
        if (specs.isEmpty()) {
            System.out.println("No specs in " + home.wishlist());
            return;
        }
        System.out.println("Loaded " + specs.size() + " spec(s) from " + home.wishlist());

        Agents agents = Wiring.agents(home, args);
        List<RunState> finished =
                new Scheduler(home.runs(), agents, home.houseRules(), Args.workers(args), Wiring.quota(home),
                        ModelTiers.load(home.root()))
                        .run(specs);

        System.out.println("Advanced " + finished.size() + " project(s)");
        ReportCommand.run(home);
        StatusCommand.run(home);
    }
}
