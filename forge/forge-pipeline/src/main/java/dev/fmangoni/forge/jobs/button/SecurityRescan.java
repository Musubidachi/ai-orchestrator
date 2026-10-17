package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.pipeline.step.ReviewStep;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.security.SecurityCheck;
import dev.fmangoni.forge.security.osv.OsvClient;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.util.Optional;

/**
 * Runs the security check on a finished project now, without moving it anywhere.
 *
 * <p>Its own job rather than a phase: an accepted project must stay accepted, and a project waiting
 * for approval should not be sent through a cross-review it has already had just to refresh its
 * security report. What it finds goes on the report, and anything serious becomes an item to rule
 * on — the repair turn belongs to the pipeline, not to a button.
 */
public final class SecurityRescan {

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;
    private final Agents agents;

    public SecurityRescan(Projects projects, JobPool pool, Shutdown shutdown, Agents agents) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
        this.agents = agents;
    }

    public String start(String id) {
        if (shutdown.isDraining()) {
            return "Forge is shutting down; start it again once it has restarted";
        }
        if (pool.isRunning(id)) {
            return "it is running; the pipeline checks security when the gate next passes";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        Project project = found.get();
        RunPaths paths = project.paths();
        RunState state = RunStore.survey(paths.stateFile()).orElse(null);
        if (state == null || !state.getPhase().terminal()) {
            return "only a finished project can be checked on demand";
        }
        ButtonJob.submit(pool, id, "security check", paths, () -> ButtonJob.locked(id, "security check", paths,
                () -> new SecurityCheck(agents, OsvClient.http())
                        .run(project.spec(), paths, new Git(paths.repo(), paths.gitLog()), state.getAssignedTo(),
                                state.getBaselineCommit(), ReviewStep.REVIEW_DIFF_CAP)));
        return "security check started";
    }
}
