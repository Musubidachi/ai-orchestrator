package dev.fmangoni.forge.cli;

import dev.fmangoni.forge.cli.command.ModelsCommand;
import dev.fmangoni.forge.cli.command.PruneCommand;
import dev.fmangoni.forge.cli.command.RankCommand;
import dev.fmangoni.forge.cli.command.ReportCommand;
import dev.fmangoni.forge.cli.command.RunCommand;
import dev.fmangoni.forge.cli.command.StatusCommand;
import dev.fmangoni.forge.cli.command.TourCommand;
import dev.fmangoni.forge.cli.command.UiCommand;
import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.cli.support.ChildGuard;
import dev.fmangoni.forge.process.output.Stamped;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.tier.ModelTiers;

/** Entry point: reads the subcommand and hands over to the class that runs it. */
public final class Forge {

    public static void main(String[] args) {
        String command = Args.commandOf(args);
        ForgeHome home = new ForgeHome(Args.rootOf(args));

        if (Args.spawnsAgents(command)) {
            // The long-running commands, whose output ends up in a log read days later.
            Stamped.install();
            ChildGuard.install(home);
            // Asks every vendor what it still offers before a single turn is bought. Only here:
            // it runs `agy models`, which reaches the network, and Antigravity is the one vendor
            // with no cache to read — so without this its retired models survive every read and
            // are found out by a run being refused.
            ModelTiers.refresh(home.root());
        }

        switch (command) {
            case "run" -> RunCommand.run(home, args);
            case "ui" -> UiCommand.run(home, args);
            case "status" -> StatusCommand.run(home);
            case "report" -> ReportCommand.run(home);
            case "rank" -> RankCommand.run(home);
            case "prune" -> PruneCommand.run(home, args);
            case "tour" -> TourCommand.run(home, args);
            case "models" -> ModelsCommand.run(home, args);
            default -> help();
        }
    }

    private static void help() {
        System.out.println("""
                forge - unattended build pipeline driving Claude Code and Codex

                  run      advance every eligible project, then write the reports
                  ui       edit the wishlist in a browser (http://localhost:7777)
                  status   one line per project
                  report   regenerate the HTML reports from state on disk
                  rank     renumber the wishlist: projects 1..P, then improvements
                  prune    delete build output from finished runs
                  tour     explain a Maven project out loud, module by module
                  models   what each vendor can actually run, and what the ladder uses

                Options:
                  --root <dir>              Forge root (default: working directory)
                  --workers <n>             projects in flight (default: 4)
                  --port <n>                editor port (default: 7777)
                  --no-resume               do not pick the queue back up on launch
                  --claude-model <alias>    e.g. opus
                  --codex-model <slug>      e.g. gpt-6-astra
                  --older-than-days <n>     prune threshold (default: 7)
                  --of <dir>                the Maven project to tour (default: forge/)
                  --only <name>             one module or class, by name
                  --video                   also record it and write an mp4
                  --no-music                a video with no music behind it
                  --refresh                 rewrite models.json from the vendors
                """);
    }

    private Forge() {
    }
}
