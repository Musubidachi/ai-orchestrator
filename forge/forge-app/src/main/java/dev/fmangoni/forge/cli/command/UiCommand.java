package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.cli.support.Wiring;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.ui.UiServer;
import dev.fmangoni.forge.ui.suggest.Suggester;
import java.util.List;
import lombok.experimental.UtilityClass;

/** {@code forge ui}: serves the editor and dashboard until interrupted. */
@UtilityClass
public class UiCommand {

    /**
     * Starts the editor, then blocks.
     *
     * <p>Blocks on the main thread deliberately: the server's own threads are daemons, so returning
     * from {@code main} would shut the editor down the instant it started.
     */
    public void run(ForgeHome home, String[] args) {
        int port = Args.option(args, "--port").map(Integer::parseInt).orElse(7777);
        Agents agents = Wiring.agentsOrNull(home, args);
        JobRunner jobs = agents == null ? null : new JobRunner(home, agents, Args.workers(args), Wiring.quota(home));
        Suggester suggester = agents == null
                ? null
                : new Suggester(agents, home.root().resolve(".scratch"), ModelTiers.load(home.root()));
        String url = new UiServer(home.wishlist(), home.runs(), port, suggester, jobs, Wiring.quota(home)).start();

        System.out.println("Wishlist editor: " + url);
        System.out.println("Writing to:      " + home.wishlist());
        resumeQueue(jobs, args);
        System.out.println("Press Ctrl+C to stop.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Picks the queue back up, so a restart is not also a stop.
     *
     * <p>Runs after the server is listening rather than before, so the dashboard is already there
     * to show what started. Says plainly what it did: work beginning on its own is exactly the
     * thing a person should not have to discover from a quota bill.
     *
     * <p>{@code --no-resume} skips it for one launch, for when the editor is being opened to look
     * at something rather than to work.
     */
    private void resumeQueue(JobRunner jobs, String[] args) {
        if (jobs == null || Args.has(args, "--no-resume")) {
            return;
        }
        List<String> started = jobs.resumeQueue();
        if (started.isEmpty()) {
            System.out.println("Queue:           nothing to resume"
                    + (jobs.isAutoRun() ? "" : " (\"Keep the queue moving\" is off)"));
            return;
        }
        System.out.println("Queue:           resumed " + String.join(", ", started));
    }
}
