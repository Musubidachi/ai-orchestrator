package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.gate.LookCheck;
import dev.fmangoni.forge.gate.OwnedFiles;
import dev.fmangoni.forge.gate.words.SameWords;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.step.GateCheck;
import dev.fmangoni.forge.pipeline.step.GateStep;
import dev.fmangoni.forge.pipeline.step.PlanStep;
import dev.fmangoni.forge.pipeline.step.QuickGate;
import dev.fmangoni.forge.pipeline.step.ReviewStep;
import dev.fmangoni.forge.pipeline.step.ScaffoldStep;
import dev.fmangoni.forge.pipeline.step.SecurityStep;
import dev.fmangoni.forge.pipeline.turn.Turns;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.GateLooks;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything Forge does to build one project, in the order it usually happens, and the one place
 * to attach something to it.
 *
 * <p>Read {@link #of} top to bottom. Each step runs in its phase and sets the next one itself,
 * because which comes next depends on what happened (a gate that fails leads to a repair, one that
 * passes to the security check); the comments say where each can lead. The usual path is the
 * order listed.
 *
 * <p>To add to the run, attach rather than edit a step: {@link #first} for what is done once before
 * any step, {@link #before} or {@link #after} a step's phase, and a {@link GateCheck} for what the
 * gate must also hold a passing build to. Adding a phase means a {@link #step} line here.
 */
public final class Outline {

    private final List<OutlineStep> first = new ArrayList<>();
    private final List<OutlineStep> before = new ArrayList<>();
    private final List<OutlineStep> steps = new ArrayList<>();
    private final List<OutlineStep> after = new ArrayList<>();

    Outline() {
    }

    /** Forge's outline for one run. */
    static Outline of(RunContext run) {
        RunPaths paths = run.paths();
        Turns turns = new Turns(run);
        GateStep gate = new GateStep(run, List.of(
                () -> SameWords.check(paths.repo()),
                () -> LookCheck.check(paths.repo(), paths.lookReference()),
                () -> OwnedFiles.check(paths.repo(), paths.gitLog(), run.state().getBaselineCommit(),
                        run.spec().owns())));
        QuickGate quickGate = new QuickGate(run, gate);
        return new Outline()
                .first("put the run back in order after a stop, a crash or a changed spec",
                        new Recovery(run)::prepare)
                .first("hold a spec whose pictures are not there", new MissingPictures(run)::holdIfAny)

                // → PLANNING
                .step(Phase.NEW, "set up the repository", new ScaffoldStep(run)::run)
                // → SCAFFOLDED, whether or not a plan came back; SUSPENDED on a usage limit
                .step(Phase.PLANNING, "plan the build, read-only, on the best model", new PlanStep(run)::run)
                // Every agent turn → GATING, or SUSPENDED when the agent could not be reached
                .step(Phase.SCAFFOLDED, "the first building turn", turns::opening)
                .step(Phase.CONTINUING, "another building turn: unfinished, not broken", turns::continueWork)
                .step(Phase.REPAIRING, "a turn to fix what the gate failed on", turns::repair)

                // → SECURITY_CHECK; CONTINUING or REPAIRING; PARKED when no turn can help
                .before(Phase.GATING, "empty the folder the screens' words are saved to",
                        () -> SameWords.clear(paths.repo()))
                .step(Phase.GATING, "run the spec's acceptance commands, then the gate checks;"
                        + " only the quick commands while the builder says unfinished", quickGate::run)
                .after(Phase.GATING, "keep what the gate asked a person to look at",
                        () -> quickGate.lastFull().ifPresent(
                                result -> GateLooks.record(run.state(), result.commands())))

                // → REVIEWING, or SECURITY_REPAIR on a serious finding
                .step(Phase.SECURITY_CHECK, "secrets, dependencies, risky code, the running app",
                        new SecurityStep(run)::run)
                .step(Phase.SECURITY_REPAIR, "one turn to fix the security findings", turns::securityRepair)
                // → DONE; REVIEW_REPAIR on a blocking finding; PARKED, or held for a person
                .step(Phase.REVIEWING, "the other vendor reviews the change", new ReviewStep(run)::run)
                .step(Phase.REVIEW_REPAIR, "a turn to fix the review's blocking findings", turns::reviewRepair);
    }

    Outline first(String what, Runnable action) {
        first.add(new OutlineStep(null, what, action));
        return this;
    }

    Outline step(Phase phase, String what, Runnable action) {
        if (stepFor(phase) != null) {
            throw new IllegalArgumentException(phase + " already has a step");
        }
        steps.add(new OutlineStep(phase, what, action));
        return this;
    }

    Outline before(Phase phase, String what, Runnable action) {
        before.add(new OutlineStep(phase, what, action));
        return this;
    }

    Outline after(Phase phase, String what, Runnable action) {
        after.add(new OutlineStep(phase, what, action));
        return this;
    }

    /** What is done once, before the first step. */
    void prepare() {
        first.forEach(entry -> entry.action().run());
    }

    /** The step for this phase, with what is attached before and after it. */
    void run(Phase phase) {
        OutlineStep step = stepFor(phase);
        if (step == null) {
            throw new IllegalStateException("cannot step from " + phase);
        }
        attachedTo(before, phase).forEach(entry -> entry.action().run());
        step.action().run();
        attachedTo(after, phase).forEach(entry -> entry.action().run());
    }

    /** Every line of the outline in order: what comes first, then each step with its attachments. */
    List<OutlineStep> lines() {
        List<OutlineStep> lines = new ArrayList<>(first);
        for (OutlineStep step : steps) {
            lines.addAll(attachedTo(before, step.phase()));
            lines.add(step);
            lines.addAll(attachedTo(after, step.phase()));
        }
        return lines;
    }

    List<OutlineStep> steps() {
        return List.copyOf(steps);
    }

    /** Attachments to a phase with no step of its own would never run. */
    List<OutlineStep> unattached() {
        List<OutlineStep> out = new ArrayList<>();
        before.stream().filter(entry -> stepFor(entry.phase()) == null).forEach(out::add);
        after.stream().filter(entry -> stepFor(entry.phase()) == null).forEach(out::add);
        return out;
    }

    private OutlineStep stepFor(Phase phase) {
        return steps.stream().filter(step -> step.phase() == phase).findFirst().orElse(null);
    }

    private static List<OutlineStep> attachedTo(List<OutlineStep> entries, Phase phase) {
        return entries.stream().filter(entry -> entry.phase() == phase).toList();
    }
}
