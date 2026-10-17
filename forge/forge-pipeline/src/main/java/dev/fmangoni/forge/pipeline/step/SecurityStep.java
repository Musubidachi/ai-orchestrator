package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.security.SecurityCheck;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.security.osv.OsvClient;
import dev.fmangoni.forge.state.RunState;

/**
 * Every security layer, then the cross-review — or the one repair turn if something is serious.
 *
 * <p>Serious findings that survive the repair are not a reason to hold the project: they go to the
 * person as items to rule on, the same way a blocking review finding does once its repair is spent.
 * Security here informs a decision; it does not take one away.
 */
public final class SecurityStep {

    private final RunContext run;

    public SecurityStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        RunState state = run.state();
        SecurityReport report = new SecurityCheck(run.agents(), OsvClient.http())
                .run(run.spec(), run.paths(), run.workspace().git(), state.getAssignedTo(),
                        state.getBaselineCommit(), ReviewStep.REVIEW_DIFF_CAP);
        boolean owed = !report.serious().isEmpty() && !state.isSecurityRepairUsed();
        state.setPhase(owed ? Phase.SECURITY_REPAIR : Phase.REVIEWING);
    }
}
