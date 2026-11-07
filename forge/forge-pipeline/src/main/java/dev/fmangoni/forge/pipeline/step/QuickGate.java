package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.gate.Gate;
import dev.fmangoni.forge.gate.GateResult;
import dev.fmangoni.forge.gate.OwnedFiles;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.Timings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gate after a turn whose builder says the work is unfinished: the quicker commands only.
 *
 * <p>There, the gate cannot end the build. A pass leads to another session ({@code
 * GateStep.moreToBuild}) and so does a failure, once the turn built something; all the verdict
 * changes is what the next brief says. Yet each of those gates ran the whole suite: 003.3 waited
 * on its browser suite, ten minutes and more, before each of its sixteen continuations.
 *
 * <p>So when the handoff says {@code partial}, the turn changed code, sessions are left and every
 * command has been timed before, this runs the spec's commands up to the first whose usual time is
 * {@link #SLOW_SECONDS} or more, and the next session starts whatever they say. Nothing after a
 * slow command runs either, because later commands lean on earlier ones. The overview class and
 * the run file are still checked; the evidence, the recordings, the gate checks (the words, the
 * look, the owned files) and what the gate asks a person to look at wait for the full gate.
 *
 * <p>It never passes a version: it leads only to CONTINUING. The full gate runs once the builder
 * says {@code complete}, when sessions run out, after an interrupted turn, and for Check Again.
 * What a quick gate left out is kept on the state as {@code notRunAtLastGate}, for the next brief
 * and the report, until the next full gate clears it.
 */
public final class QuickGate {

    /** A command whose usual time is this many seconds or more is left for the full gate. */
    public static final int SLOW_SECONDS = 120;

    private final RunContext run;
    private final GateStep full;
    private boolean quickLast;

    /** @param full the gate this one stands in for, run whenever a quick one cannot be */
    public QuickGate(RunContext run, GateStep full) {
        this.run = run;
        this.full = full;
    }

    public void run() {
        RunState state = run.state();
        List<String> acceptance = run.acceptance();
        int quick = quickCount(acceptance);
        quickLast = quick > 0;
        if (!quickLast) {
            state.setNotRunAtLastGate(new ArrayList<>());
            full.run();
            return;
        }
        GateResult result = gate().run(acceptance.subList(0, quick), List.of(), overview(state.getPlan()),
                List.of());
        Timings.record(state, result.commands());
        state.setNotRunAtLastGate(new ArrayList<>(acceptance.subList(quick, acceptance.size())));
        state.setLastFailure(result.firstFailure().orElse(null));
        state.setLastError(result.passed() ? null : result.summary());
        state.setContinuations(state.getContinuations() + 1);
        state.setPhase(Phase.CONTINUING);
    }

    /**
     * The full gate's verdict when it is the one that ran last. Empty after a quick gate, which
     * leaves what the last full gate found in place.
     */
    public Optional<GateResult> lastFull() {
        return quickLast ? Optional.empty() : Optional.ofNullable(full.lastResult());
    }

    /** How many commands, from the first, this gate runs; none when it has to be the full gate. */
    private int quickCount(List<String> acceptance) {
        if (!unfinished()) {
            return 0;
        }
        int quick = leadingQuick(acceptance, run.state().getCommandSeconds());
        if (quick == 0 || quick == acceptance.size()) {
            return 0;
        }
        // Asked last, because it is the question that runs git.
        return OwnedFiles.builtSince(run.workspace().git(), run.state().getPreTurnCommit()) ? quick : 0;
    }

    /** The builder says there is more to do, and nothing else asks for the full gate. */
    private boolean unfinished() {
        RunState state = run.state();
        Plan plan = state.getPlan();
        return plan != null && plan.usable() && "partial".equalsIgnoreCase(plan.getStatus())
                && !state.isGateOnly() && !state.isRecoveredTurn()
                && state.getSessions() < state.sessionLimit(run.spec().maxSessions());
    }

    /**
     * How many commands, from the first, come before the first slow one. None unless every command
     * has been timed: one that never has may be the slow one, and a spec that gains a command
     * should see it run.
     */
    static int leadingQuick(List<String> acceptance, Map<String, List<Integer>> seconds) {
        if (seconds == null || acceptance.stream()
                .anyMatch(command -> seconds.getOrDefault(command, List.of()).isEmpty())) {
            return 0;
        }
        int quick = 0;
        while (quick < acceptance.size() && usual(seconds.get(acceptance.get(quick))) < SLOW_SECONDS) {
            quick++;
        }
        return quick;
    }

    /** The middle of a command's times, so one slow run does not make it slow, nor one quick run quick. */
    private static int usual(List<Integer> seen) {
        List<Integer> sorted = new ArrayList<>(seen);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    /** The overview class the handoff named, as the full gate is given it. */
    private static String overview(Plan plan) {
        return plan.getOverview() == null ? "" : plan.getOverview();
    }

    private Gate gate() {
        RunPaths paths = run.paths();
        return new Gate(paths.repo(), paths.logs(), paths.evidenceForSession(run.state().getSessions()),
                paths.gateLog(), run.spec().gateTimeout());
    }
}
