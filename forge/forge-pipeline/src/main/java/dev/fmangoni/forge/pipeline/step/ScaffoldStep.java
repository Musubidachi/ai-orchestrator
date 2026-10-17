package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.project.Phase;

/** Prepares the repository for the planning turn, and records the commit everything is measured from. */
public final class ScaffoldStep {

    private final RunContext run;

    public ScaffoldStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        run.state().setBaselineCommit(run.spec().isImprovement()
                ? run.workspace().scaffoldImprovement(run.spec(), run.acceptance())
                : run.workspace().scaffold(run.spec()));
        run.state().setPhase(Phase.PLANNING);
    }
}
