package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.MissingClips;
import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.gate.Gate;
import dev.fmangoni.forge.gate.GateResult;
import dev.fmangoni.forge.gate.LookCheck;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.Timings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The decision point. Nothing the agent reported reaches this: the verdict is the exit codes of the
 * spec's own commands, run against the files on disk.
 */
public final class GateStep {

    private final RunContext run;

    public GateStep(RunContext run) {
        this.run = run;
    }

    public void run() {
        RunState state = run.state();
        List<Feature> owed = owedRecordings();
        GateResult result = withDefaultLook(newGate().run(run.acceptance(), run.evidence(), overview(), owed));
        // Recorded whether it passed or failed: a command that got slower is worth knowing about
        // even on a run that went green, and especially on one that did not.
        Timings.record(state, result.commands());
        Optional<String> moved = rememberClips(result, owed);

        boolean afterInterruption = state.isRecoveredTurn();
        state.setRecoveredTurn(false);
        boolean gateOnly = state.isGateOnly();
        state.setGateOnly(false);
        // "Still want it" answered the last gate's question. This gate asks its own.
        state.setKeptClips(new ArrayList<>());
        if (result.passed()) {
            state.setLastFailure(null);
            state.setLastError(null);
            state.setPhase(Phase.SECURITY_CHECK);
            return;
        }
        state.setLastFailure(result.firstFailure().orElse(null));
        state.setLastError(result.summary());
        if (LookCheck.failed(state.getLastFailure()) && state.getExtraKind() == null) {
            // Everything else passed: this check only runs on a gate that did. What differs is the
            // default look, against a reference kept outside the repository, where no agent can
            // reach it. So another session can only undo work that may have been asked for, or
            // change nothing: 003.4 added the navigation bar it was asked to, and spent its last
            // three sessions on this with no code changed. Whether the look was meant to change
            // is the person's call. Not while an extra is being added: its brief is to leave the
            // default look as it is, so a changed one is a slip the agent puts right, like any
            // other failing check, and falls through to the repair below.
            park("only the default look differs from the one kept for it ("
                    + state.getLastFailure().command().substring(LookCheck.FAILURE.length())
                    + "): approve the new look if the change was meant, or send it back if it was not");
        } else if (gateOnly) {
            // Check again asked a question of the gate, not of an agent. The answer is no, so the
            // project goes back to waiting for the person who asked, with what the gate said.
            park("the gate was checked again and still fails: " + result.summary());
        } else if (moved.isPresent()) {
            // Another session would move the clip back. Only a person can say which name stays.
            park(moved.get());
        } else {
            afterFailure(afterInterruption);
        }
    }

    /**
     * Keeps which clips this gate owed and found missing, and says whether one moved since the last.
     *
     * <p>Only a gate that reached the recordings check knows anything about clips. One stopped by a
     * failing command never looked, and forgetting the last gate's answer because of it would hide
     * the very back-and-forth this is here to catch.
     */
    private Optional<String> rememberClips(GateResult result, List<Feature> owed) {
        if (!result.commands().stream().allMatch(command -> command.passed() || synthetic(command))) {
            return Optional.empty();
        }
        RunState state = run.state();
        List<RecordingTake> missing = Features.unrecorded(run.paths().repo(), owed);
        Optional<String> moved = MissingClips.moved(listOf(state.getOwedAtLastGate()),
                listOf(state.getUnfilmedAtLastGate()), owed, missing);
        state.setOwedAtLastGate(owed.stream().map(Feature::id).toList());
        state.setUnfilmedAtLastGate(missing.stream().map(take -> take.feature().id()).toList());
        return moved;
    }

    /**
     * The gate's verdict, held to the project's default look once it has one ({@link LookCheck}).
     *
     * <p>Only on a gate that passed: a reference adopted from a failing build would hold the project
     * to a look nobody accepted, and a failing gate already has a reason to give.
     */
    private GateResult withDefaultLook(GateResult result) {
        if (!result.passed()) {
            return result;
        }
        return LookCheck.check(run.paths().repo(), run.paths().lookReference())
                .map(changed -> {
                    List<CommandResult> commands = new ArrayList<>(result.commands());
                    commands.add(changed);
                    return new GateResult(commands, result.evidence());
                })
                .orElse(result);
    }

    /** The checks the gate adds after the commands, which have no script of their own. */
    private static boolean synthetic(CommandResult command) {
        return command.script() == null || command.script().isBlank();
    }

    private static List<String> listOf(List<String> ids) {
        return ids == null ? List.of() : ids;
    }

    /**
     * The overview class the handoff named.
     *
     * <p>Null and blank mean different things here, and the gate says something different about
     * each: null is "no handoff arrived at all", which is what an interrupted turn leaves behind,
     * and blank is "a handoff arrived and named no overview class". Told the second when the first
     * is true, the agent goes looking for a field it never got as far as writing — and so does the
     * person reading the report.
     */
    private String overview() {
        Plan plan = run.state().getPlan();
        return plan == null ? null : plan.getOverview() == null ? "" : plan.getOverview();
    }

    private void afterFailure(boolean afterInterruption) {
        RunState state = run.state();
        Spec spec = run.spec();
        if (state.getSessions() >= spec.maxSessions()) {
            park("still incomplete after " + state.getSessions() + " sessions");
        } else if (afterInterruption) {
            state.setPhase(Phase.REPAIRING);
        } else if (unfinishedRatherThanBroken()) {
            state.setContinuations(state.getContinuations() + 1);
            state.setPhase(Phase.CONTINUING);
        } else if (state.getRepairRounds() >= spec.maxRepairRounds()) {
            park("acceptance still failing after " + state.getRepairRounds() + " repair rounds");
        } else {
            state.setRepairRounds(state.getRepairRounds() + 1);
            state.setRepairRoundsEver(state.getRepairRoundsEver() + 1);
            state.setPhase(Phase.REPAIRING);
        }
    }

    /**
     * Distinguishes a project that needs more time from one that is stuck.
     *
     * <p>Both requirements matter. The agent must have said the work was unfinished, and the turn
     * must actually have changed something — otherwise an agent that reports "partial" every time
     * would loop until the session budget ran out, having built nothing. Progress is measured
     * against the tree, not against the claim.
     */
    private boolean unfinishedRatherThanBroken() {
        Plan plan = run.state().getPlan();
        boolean saysUnfinished = plan != null && plan.usable() && plan.partial();
        return saysUnfinished && run.workspace().git().changedSince(run.state().getPreTurnCommit());
    }

    /**
     * The features somebody has approved, which therefore owe a recording.
     *
     * <p>Read off disk at every gate rather than carried in the run state, because approving
     * happens in a browser while the project may be mid-turn, and the file is the only place both
     * sides can meet. An approval given during a build is honoured by that build's own gate.
     */
    private List<Feature> owedRecordings() {
        return Features.owed(Features.knownTo(run.state().getPlan()),
                DecisionStore.load(run.paths().featuresFile()));
    }

    private Gate newGate() {
        RunPaths paths = run.paths();
        return new Gate(paths.repo(), paths.logs(), paths.evidenceForSession(run.state().getSessions()),
                paths.gateLog(), run.spec().gateTimeout());
    }

    private void park(String reason) {
        run.state().setPhase(Phase.PARKED);
        run.state().setLastError(reason);
    }
}
