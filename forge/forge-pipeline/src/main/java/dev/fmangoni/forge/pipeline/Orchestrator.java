package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.pipeline.step.GateStep;
import dev.fmangoni.forge.pipeline.step.PlanStep;
import dev.fmangoni.forge.pipeline.step.ReviewStep;
import dev.fmangoni.forge.pipeline.step.ScaffoldStep;
import dev.fmangoni.forge.pipeline.step.SecurityStep;
import dev.fmangoni.forge.pipeline.turn.Turns;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelChoice;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Drives one project through the pipeline, persisting after every transition.
 *
 * <p>Each phase names the action still owed, and every step draws its inputs from the persisted
 * state rather than from fields. That is what makes the run genuinely resumable: the process can be
 * killed between any two steps — or stopped for six hours by a quota window — and the next
 * invocation continues without repeating work already paid for.
 *
 * <p>This class is the loop and the dispatch only. Each phase's work is its own class:
 * {@link ScaffoldStep}, {@link PlanStep}, {@link Turns}, {@link GateStep}, {@link SecurityStep},
 * {@link ReviewStep};
 * and {@link Recovery} puts the run in order before the first step.
 */
public final class Orchestrator {

    private final RunContext run;

    /**
     * Called after every phase transition, once the new state is on disk.
     *
     * <p>Exists so the report can be rewritten as the run proceeds rather than only when it ends.
     * A project spanning a dozen sessions is unreadable if its report only appears at the finish,
     * and a suspended or stopped run would otherwise leave none at all.
     *
     * <p>A consumer rather than a direct call into the report package: the orchestrator's job is
     * the state machine, and it should not care that anyone is watching.
     */
    private final Consumer<RunState> afterEachStep;

    /**
     * Asked between steps: true means stop at this boundary rather than start the next step.
     *
     * <p>How a shutdown keeps its promise. It waits for the agent turns in flight and then exits —
     * but without this the loop went straight on into the next step, and a gate that failed a
     * moment after the shutdown looked started a repair turn it then killed. That is the exact
     * loss the shutdown exists to prevent. Stopping here is always safe: the state was saved as
     * the last step ended, and the next start resumes from it.
     */
    private final BooleanSupplier stopRequested;

    private final Recovery recovery;
    private final ScaffoldStep scaffold;
    private final PlanStep plan;
    private final Turns turns;
    private final GateStep gate;
    private final SecurityStep security;
    private final ReviewStep review;

    public Orchestrator(Spec spec, RunPaths paths, RunState state, Agents agents, Workspace workspace,
                        Wishlist wishlist, ModelTiers tiers, Consumer<RunState> afterEachStep) {
        this(spec, paths, state, agents, workspace, wishlist, tiers, afterEachStep, () -> false);
    }

    public Orchestrator(Spec spec, RunPaths paths, RunState state, Agents agents, Workspace workspace,
                        Wishlist wishlist, ModelTiers tiers, Consumer<RunState> afterEachStep,
                        BooleanSupplier stopRequested) {
        this.run = new RunContext(spec, paths, state, agents, workspace, wishlist,
                new ModelChoice(tiers, spec));
        this.afterEachStep = afterEachStep;
        this.stopRequested = stopRequested;
        this.recovery = new Recovery(run);
        this.scaffold = new ScaffoldStep(run);
        this.plan = new PlanStep(run);
        this.turns = new Turns(run);
        this.gate = new GateStep(run);
        this.security = new SecurityStep(run);
        this.review = new ReviewStep(run);
    }

    /**
     * @return the state as it stands when the project finishes, suspends, or parks — or where it
     *         was when a stop was requested between two steps
     */
    public RunState advance() {
        recovery.prepare();
        while (!halted() && !stopRequested.getAsBoolean()) {
            timedStep();
            RunStore.save(run.paths().stateFile(), run.state());
            notifyProgress();
        }
        notifyProgress();
        return run.state();
    }

    /**
     * Whether this run has gone as far as it can without someone.
     *
     * <p>A hold for a person counts whatever the phase. It can sit on a phase that would otherwise
     * run — a review repair after the findings' attempts ran out, a review whose reply could not be
     * read — and a loop that only stopped at terminal or suspended phases went straight round again,
     * which is the exact thing the hold exists to prevent.
     */
    private boolean halted() {
        Phase phase = run.state().getPhase();
        return phase.terminal() || phase == Phase.SUSPENDED || run.state().getNeedsPerson() != null;
    }

    /**
     * One step, with the time it took added to the project's total.
     *
     * <p>Wrapped here rather than inside each turn so that every kind of work is covered by
     * construction — an agent turn, the gate, the review, the scaffold — and so that a phase added
     * later is counted without anyone remembering to. The alternative, instrumenting each turn,
     * is exactly the arrangement that quietly stops being complete.
     *
     * <p>A step that failed still counts. The time was spent whether or not it bought anything,
     * and a project that has burned an hour failing is precisely the one worth seeing.
     */
    private void timedStep() {
        Instant began = Instant.now();
        try {
            step();
        } finally {
            RunState state = run.state();
            state.setWorkedMillis(state.getWorkedMillis() + Duration.between(began, Instant.now()).toMillis());
        }
    }

    private void step() {
        switch (run.state().getPhase()) {
            case NEW -> scaffold.run();
            case PLANNING -> plan.run();
            case SCAFFOLDED -> turns.opening();
            case REPAIRING -> turns.repair();
            case CONTINUING -> turns.continueWork();
            case GATING -> gate.run();
            case SECURITY_CHECK -> security.run();
            case SECURITY_REPAIR -> turns.securityRepair();
            case REVIEWING -> review.run();
            case REVIEW_REPAIR -> turns.reviewRepair();
            default -> throw new IllegalStateException("cannot step from " + run.state().getPhase());
        }
    }

    /** A watcher failing must not take the run down with it; the state file is the real record. */
    private void notifyProgress() {
        if (afterEachStep == null) {
            return;
        }
        try {
            afterEachStep.accept(run.state());
        } catch (RuntimeException e) {
            System.err.println("progress listener failed: " + e.getMessage());
        }
    }
}
