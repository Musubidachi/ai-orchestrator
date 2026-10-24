package dev.fmangoni.forge.jobs.publish;

import dev.fmangoni.forge.deploy.github.Branches;
import dev.fmangoni.forge.deploy.github.GitHubCli;
import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.deploy.shelf.Shelver;
import dev.fmangoni.forge.jobs.button.ButtonJob;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Shelves a whole family on GitHub to free this machine, or brings it back, because a person
 * pressed Shelve or Resume.
 *
 * <p>The work is {@link Shelver}'s, on a worker under the repository's lock. Like everything that
 * reaches GitHub it needs publishing switched on, and nothing of the family may be running: shelving
 * deletes the files a running version would be working in.
 */
public final class ShelfJob {

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;

    public ShelfJob(Projects projects, JobPool pool, Shutdown shutdown) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
    }

    public String shelve(String id) {
        return start(id, true);
    }

    public String resume(String id) {
        return start(id, false);
    }

    private String start(String id, boolean away) {
        if (!Publisher.enabled(projects.home().root())) {
            return "shelving goes through GitHub, and publishing to GitHub is switched off: it is turned on in "
                    + Publisher.SETTINGS;
        }
        if (shutdown.isDraining()) {
            return "Forge is shutting down; try again once it has restarted";
        }
        if (projects.find(id).isEmpty()) {
            return Projects.unknown(id);
        }
        String family = Branches.familyOf(id);
        Optional<String> running = pool.ids().stream().filter(other -> Branches.familyOf(other).equals(family)).findFirst();
        if (running.isPresent()) {
            return running.get() + " is running: " + (away ? "shelve " : "resume ") + family + " once it stops";
        }
        Optional<Path> gh = GitHubCli.find(System.getenv());
        if (gh.isEmpty()) {
            return "gh is not installed: install the GitHub CLI and run gh auth login yourself";
        }
        Path runs = projects.home().runs();
        RunPaths paths = RunPaths.under(runs, family);
        String what = away ? "shelve" : "resume from GitHub";
        ButtonJob.submit(pool, family, what, paths, () -> ButtonJob.locked(family, what, paths, () -> {
            GitHubCli github = new GitHubCli(gh.get(), runs, paths.root().resolve("shelf.log"));
            Shelver shelver = new Shelver(runs, family, github);
            System.out.println("[" + family + "] " + (away ? shelver.shelve() : shelver.resume()));
        }));
        return away ? "shelving " + family + " on GitHub" : "bringing " + family + " back from GitHub";
    }
}
