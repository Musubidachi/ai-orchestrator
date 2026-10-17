package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.deploy.Deployer;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.marketing.KitStatus;
import dev.fmangoni.forge.marketing.MarketingKit;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.util.Optional;

/**
 * Writes a marketing kit for an accepted project: one agent turn in its own folder, then a check.
 *
 * <p>On a button, like everything else outward. The kit itself publishes nothing; this still spends
 * an agent call, so it is not something the queue does on its own.
 */
public final class MarketingJob {

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;
    private final Agents agents;

    public MarketingJob(Projects projects, JobPool pool, Shutdown shutdown, Agents agents) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
        this.agents = agents;
    }

    public String start(String id) {
        if (shutdown.isDraining()) {
            return "Forge is shutting down; try again once it has restarted";
        }
        if (pool.isRunning(id)) {
            return "it is running; make the kit once it stops";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        Project project = found.get();
        RunPaths paths = project.paths();
        RunState state = RunStore.survey(paths.stateFile()).orElse(null);
        String blocked = MarketingKit.blockedReason(state);
        if (blocked != null) {
            return blocked;
        }
        ButtonJob.submit(pool, id, "marketing kit", paths, () -> {
            KitStatus status = new MarketingKit(agents)
                    .make(project.spec(), paths, state, Deployer.headOf(paths.repo()));
            System.out.println("[" + id + "] marketing kit: " + status.outcome()
                    + (status.detail().isBlank() ? "" : " — " + status.detail()));
        });
        return "writing the marketing kit";
    }
}
