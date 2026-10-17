package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.deploy.Deployer;
import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunStore;
import java.util.Optional;

/**
 * Deploys an accepted project, because someone pressed Deploy and confirmed the command.
 *
 * <p>The only path to a deploy. Nothing in the pipeline calls this, and it re-checks every
 * precondition here rather than trusting the button: the page was drawn up to four seconds ago.
 */
public final class DeployJob {

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;

    public DeployJob(Projects projects, JobPool pool, Shutdown shutdown) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
    }

    public static Deployer deployerFor(Spec spec, RunPaths paths) {
        return new Deployer(spec, paths, new Git(paths.repo(), paths.gitLog()), System.getenv());
    }

    public String start(String id) {
        if (shutdown.isDraining()) {
            return "Forge is shutting down; deploy again once it has restarted";
        }
        if (pool.isRunning(id)) {
            return "it is running; deploy once it stops";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        RunPaths paths = found.get().paths();
        Deployer deployer = deployerFor(found.get().spec(), paths);
        String blocked = deployer.blockedReason(RunStore.survey(paths.stateFile()).orElse(null));
        if (blocked != null) {
            return blocked;
        }
        ButtonJob.submit(pool, id, "deploy", paths, () -> ButtonJob.locked(id, "deploy", paths, () -> {
            Deployment result = deployer.deploy();
            System.out.println("[" + id + "] deploy " + result.number() + ": " + result.outcome()
                    + (result.url().isBlank() ? "" : " — " + result.url()));
        }));
        return "deploy started";
    }
}
