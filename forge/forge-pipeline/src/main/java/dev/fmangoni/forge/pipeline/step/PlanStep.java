package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.brief.PlanBrief;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Suspension;
import dev.fmangoni.forge.pipeline.turn.PlanText;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Decides how the project will be built, once, on the best model the building vendor has.
 *
 * <p>Read-only: the plan is prose, and this turn writes no code. Forge saves what comes back to
 * {@code plan.md} beside the run, and every implementation turn afterwards is handed it — which is
 * what lets those turns run on a cheaper model without also handing the decisions to it.
 *
 * <p>Never fatal. A planner that is out of quota suspends the project the way any turn does, and
 * one that answers with nothing leaves no plan and lets the build proceed exactly as it did before
 * this phase existed. A missing plan is a worse turn, not a broken project.
 */
public final class PlanStep {

    /**
     * Wall clock for the planning turn.
     *
     * <p>Capped like a review rather than like a build: this is reading and writing prose, and a
     * planner still going after ten minutes is not deliberating.
     */
    private static final Duration PLAN_CAP = Duration.ofMinutes(10);

    private final RunContext run;

    public PlanStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        RunState state = run.state();
        if (alreadyPlanned()) {
            // A plan on disk is a plan: one written by hand, or one this project already paid for
            // before something interrupted it. Either way, buying a second opinion on it from the
            // most expensive model available is not what the person who left it there wanted.
            state.setPhase(Phase.SCAFFOLDED);
            return;
        }
        Agent planner = run.agents().get(state.getAssignedTo());
        Duration cap = shorterOf(PLAN_CAP, run.spec().agentTimeout());
        String model = run.models().plan(planner.kind());

        AgentResult result = planner.run(
                AgentTask.readOnly(run.paths().repo(), PlanBrief.of(run.spec(), cap), cap, run.paths().logs())
                        .using(model));

        if (result.blockedExternally()) {
            // The same treatment any turn gets: come back when the window reopens, in this phase —
            // or wait for a person, when no window will fix it.
            Suspension.after(state, Phase.PLANNING, result);
            return;
        }
        write(result.finalMessage(), planner.kind() + (model.isBlank() ? "" : " (" + model + ")"));
        state.setPhase(Phase.SCAFFOLDED);
    }

    private boolean alreadyPlanned() {
        return !PlanText.section(run.paths()).isBlank();
    }

    private void write(String plan, String planner) {
        if (plan == null || plan.isBlank()) {
            System.out.println("[" + run.spec().id() + "] the planning turn returned nothing; "
                    + "building without a plan");
            return;
        }
        try {
            Files.writeString(run.paths().planFile(),
                    "<!-- planned by " + planner + " -->\n\n" + plan.strip() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The plan is context for later turns, not state anything depends on.
            throw new UncheckedIOException("cannot write " + run.paths().planFile(), e);
        }
    }

    private static Duration shorterOf(Duration one, Duration other) {
        return one.compareTo(other) <= 0 ? one : other;
    }
}
