package dev.fmangoni.forge.jobs.publish;

import dev.fmangoni.forge.deploy.github.GitHubCli;
import dev.fmangoni.forge.deploy.github.Publication;
import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.jobs.button.ButtonJob;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Puts a family's approved versions on GitHub, because a version was just approved or a person
 * pressed Publish.
 *
 * <p>Those are its only two callers. Approving a version is what publishes it: the person asked for
 * that to be one act. The work itself is {@link Publisher}'s, run on a worker under the
 * repository's lock so no turn moves a branch while it pushes.
 */
public final class PublishJob {

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;

    public PublishJob(Projects projects, JobPool pool, Shutdown shutdown) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
    }

    /** Whether this Forge publishes to GitHub at all; see {@link Publisher#enabled}. */
    public boolean enabled() {
        return Publisher.enabled(projects.home().root());
    }

    public String start(String id) {
        if (!enabled()) {
            return "publishing to GitHub is switched off: it is turned on in " + Publisher.SETTINGS;
        }
        if (shutdown.isDraining()) {
            return "Forge is shutting down; publish again once it has restarted";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        Optional<Path> gh = GitHubCli.find(System.getenv());
        if (gh.isEmpty()) {
            return "gh is not installed: install the GitHub CLI and run gh auth login yourself";
        }
        RunPaths paths = found.get().paths();
        Path runs = projects.home().runs();
        ButtonJob.submit(pool, id, "publish", paths, () -> ButtonJob.locked(id, "publish", paths, () -> {
            GitHubCli github = new GitHubCli(gh.get(), paths.repo(), paths.logs().resolve("github.log"));
            Publication result = new Publisher(runs, id, github).publish();
            System.out.println("[" + id + "] publish: " + result.outcome()
                    + (result.detail().isBlank() ? "" : " - " + result.detail()));
        }));
        return "publishing to GitHub";
    }
}
