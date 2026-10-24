package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.deploy.github.GitHubCli;
import dev.fmangoni.forge.deploy.github.Publication;
import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.project.Phase;
import java.nio.file.Path;
import java.util.Optional;

/** Prepares the repository for the planning turn, and records the commit everything is measured from. */
public final class ScaffoldStep {

    private final RunContext run;

    public ScaffoldStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        if (run.spec().isImprovement()) {
            String first = run.workspace().scaffoldImprovement(run.spec(), run.acceptance());
            run.state().setBaselineCommit(first);
            branchOnGitHub(first);
        } else {
            run.state().setBaselineCommit(run.workspace().scaffold(run.spec()));
        }
        run.state().setPhase(Phase.PLANNING);
    }

    /**
     * Makes the enhancement's branch on GitHub, now that it has begun, at the commit it began with.
     *
     * <p>Only when the family publishes. Starting waited for its repository, so that is there. A
     * push that does not go through is said and made by the next publish, and never stops the work:
     * a branch is a record of the enhancement, not a condition of building it.
     */
    private void branchOnGitHub(String first) {
        String id = run.spec().id();
        Path runs = run.paths().root().getParent();
        if (!Publisher.enabled(runs.getParent())) {
            return;
        }
        Optional<Path> gh = GitHubCli.find(System.getenv());
        if (gh.isEmpty()) {
            System.err.println("[" + id + "] no branch on GitHub yet: gh is not installed");
            return;
        }
        try {
            GitHubCli github = new GitHubCli(gh.get(), run.paths().repo(), run.paths().logs().resolve("github.log"));
            Publication result = new Publisher(runs, id, github).begin(id, run.spec().title(), first);
            System.out.println("[" + id + "] its branch on GitHub: " + result.outcome()
                    + (result.detail().isBlank() ? "" : " - " + result.detail()));
        } catch (RuntimeException e) {
            System.err.println("[" + id + "] no branch on GitHub yet: " + e.getMessage());
        }
    }
}
