package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.jobs.unattended.Prune;
import dev.fmangoni.forge.project.ForgeHome;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * {@code forge prune}: deletes build output from runs finished a while ago.
 *
 * <p>Kept as an explicit command rather than running automatically after {@code run}. Deleting
 * files unattended on a machine holding the user's real repositories is worth an explicit
 * instruction until the pipeline has earned some trust.
 */
@UtilityClass
public class PruneCommand {

    public void run(ForgeHome home, String[] args) {
        Duration age = Duration.ofDays(Args.option(args, "--older-than-days").map(Integer::parseInt).orElse(7));
        long freed = Prune.run(home.runs(), age);
        System.out.printf("Reclaimed %.1f MB from runs finished over %d day(s) ago%n",
                freed / (1024.0 * 1024.0), age.toDays());
    }
}
