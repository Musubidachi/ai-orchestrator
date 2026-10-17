package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.brief.BuildBrief;
import dev.fmangoni.forge.brief.ContinueBrief;
import dev.fmangoni.forge.brief.ImproveBrief;
import dev.fmangoni.forge.brief.repair.FeedbackBrief;
import dev.fmangoni.forge.brief.repair.RepairBrief;
import dev.fmangoni.forge.brief.repair.ReviewRepairBrief;
import dev.fmangoni.forge.brief.repair.SecurityRepairBrief;
import dev.fmangoni.forge.pipeline.run.ReviewLoop;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.util.List;

/** The building turns, one per phase that owes one: which prompt each sends, and what it commits as. */
public final class Turns {

    private final RunContext run;
    private final AgentTurn turn;

    public Turns(RunContext run) {
        this.run = run;
        this.turn = new AgentTurn(run);
    }

    public void opening() {
        Spec spec = run.spec();
        RunState state = run.state();
        turn.run(spec.isImprovement()
                ? ImproveBrief.of(spec, spec.agentTimeout(), state.getPlan())
                : BuildBrief.of(spec, spec.agentTimeout(), state.getPlan()), "Build");
    }

    public void repair() {
        Spec spec = run.spec();
        RunState state = run.state();
        turn.run(RepairBrief.of(state.getLastFailure(), state.getRepairRounds(),
                        spec.maxRepairRounds(), spec.agentTimeout(), state.getPlan()),
                "Repair round " + state.getRepairRounds());
    }

    public void continueWork() {
        Spec spec = run.spec();
        RunState state = run.state();
        String feedback = state.getUserFeedback();
        if (feedback != null && !feedback.isBlank()) {
            // Cleared before the turn rather than after: if the process dies mid-turn the work is
            // recovered and gated, and re-delivering the same request would have it done twice.
            state.setUserFeedback(null);
            turn.run(FeedbackBrief.of(feedback, state.getPlan(), spec.agentTimeout()), "Requested changes");
            return;
        }
        turn.run(ContinueBrief.of(state.getLastFailure(), state.getPlan(),
                        state.getSessions() + 1, spec.maxSessions(), spec.agentTimeout()),
                "Continue (session " + (state.getSessions() + 1) + ")");
    }

    public void securityRepair() {
        SecurityReport report = SecurityReport.load(run.paths().securityFile());
        run.state().setSecurityRepairUsed(true);
        turn.run(SecurityRepairBrief.of(report == null ? List.of() : report.serious(), run.spec().agentTimeout()),
                "Security repair");
    }

    public void reviewRepair() {
        run.state().setReviewRepairUsed(true);
        turn.run(ReviewRepairBrief.of(run.state().getBlockingFindings(), ReviewLoop.MAX_ATTEMPTS,
                        run.spec().agentTimeout()),
                "Review repair");
    }
}
