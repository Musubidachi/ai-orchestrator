package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.Usage;
import dev.fmangoni.forge.brief.RunFileBrief;
import dev.fmangoni.forge.brief.SpecChangeBrief;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.pipeline.run.Suspension;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * Runs the assigned agent, commits whatever it produced, and hands the result to the gate.
 *
 * <p>The commit happens before the outcome is judged. Even a turn that timed out leaves work worth
 * keeping, and an uncommitted workspace would make the next round's diff meaningless.
 */
final class AgentTurn {

    private final RunContext run;

    AgentTurn(RunContext run) {
        this.run = run;
    }

    void run(String brief, String commitMessage) {
        RunState state = run.state();
        RunPaths paths = run.paths();
        Agent agent = run.agents().get(state.getAssignedTo());
        beforeTurn();

        // The plan goes in front of the brief, and the model comes from what the project has
        // already spent: a turn after two failed repair rounds is asking a stronger model than the
        // one that failed them. See ModelChoice. A project with no run file yet is told so here,
        // whatever the turn is for: see RunFileBrief. A change to the spec goes first of all,
        // because it outranks everything after it: see SpecChangeBrief.
        String specChange = state.getSpecChange() == null ? "" : state.getSpecChange();
        // A new vendor taking the project over hears that before anything else (see Recovery).
        String takeover = state.getTakeover() == null || state.getTakeover().isBlank() ? "" : state.getTakeover() + "\n\n";
        String model = run.models().implement(agent.kind(), state.getRepairRounds());
        AgentResult result = agent.run(
                AgentTask.writable(paths.repo(),
                                takeover + specChange + brief + RunFileBrief.ifOwed(paths.repo()) + PlanText.section(paths),
                                run.spec().agentTimeout(), paths.logs())
                        .continuing(Conversation.sessionFor(state, agent.kind()))
                        .using(model));

        run.workspace().git().commitAll(commitMessage + " (" + agent.kind() + ")");
        state.setTurnInFlight(false);
        countTokens(state, agent.kind(), model, result);
        Conversation.remember(state, agent.kind(), result.sessionId());

        if (result.blockedExternally()) {
            Suspension.after(state, state.getPhase(), result);
        } else if (invocationFailed(result)) {
            backOff(result);
        } else {
            completed(result);
        }
    }

    /**
     * Adds what the vendor said this turn cost to the project's total and to its model's share.
     *
     * <p>Both come off the one figure the vendor reported, so the split can never disagree with the
     * total it was taken from. A blank model means the ladder had nothing for this vendor and the
     * CLI chose for itself, which is worth seeing as itself rather than filed under a guess.
     */
    private static void countTokens(RunState state, AgentKind kind, String model, AgentResult result) {
        long moved = Usage.from(kind, result.proc() == null ? "" : result.proc().stdout()).totalTokens();
        if (moved <= 0) {
            return;
        }
        state.setTokensSpent(state.getTokensSpent() + moved);
        // A state.json written with an explicit null here deserialises as one, and losing the turn
        // to it would be exactly the silent drop the house rules are about. Make the map instead.
        if (state.getTokensByModel() == null) {
            state.setTokensByModel(new LinkedHashMap<>());
        }
        String named = model == null || model.isBlank() ? "the CLI's own default" : model;
        state.getTokensByModel().merge(kind + "/" + named, moved, Long::sum);
    }

    private void beforeTurn() {
        RunState state = run.state();
        // The house rules are copied into the workspace as CLAUDE.md and AGENTS.md, and used to be
        // written once, at scaffold. So every rule added afterwards reached new projects only —
        // and the six already running here carried the rules as they stood on the day they began,
        // for ever. Rewritten before each turn instead, which is also the only reason a gate
        // message may point at a section of AGENTS.md and expect it to be there.
        run.workspace().refreshRules(run.spec());
        run.workspace().git().keepOutOfHistory(Feature.RECORDINGS);
        // The spec as the person has it now, in the repository and in the brief. Kept on the state
        // until a session has actually been told, since the copy is already current by the next turn.
        run.workspace().refreshSpec(run.spec(), run.acceptance()).ifPresent(change -> {
            String notice = SpecChangeBrief.of(change.file(), change.diff());
            String earlier = state.getSpecChange();
            state.setSpecChange(earlier == null || earlier.isBlank() ? notice : earlier + notice);
        });

        // Recorded before the agent starts: if this process dies mid-turn, the next run needs to
        // know the work on disk was produced by an agent rather than by a gate.
        state.setTurnInFlight(true);
        state.setPreTurnCommit(run.workspace().git().head());
        RunStore.save(run.paths().stateFile(), state);
    }

    private void completed(AgentResult result) {
        RunState state = run.state();
        // Counted only now, once the call is known to have reached a model and done something.
        //
        // A rejected call is not a session. Measured here: one project recorded seven sessions
        // against a budget meant to stop it running forever, and seven of the eight invocations
        // behind that number were 2.5-second "you've hit your session limit" replies. Charging
        // those to the budget parks a healthy project for the vendor's scheduling rather than for
        // anything it did — the same mistake repair rounds used to make.
        state.setSessions(state.getSessions() + 1);
        state.setSessionsOnConversation(state.getSessionsOnConversation() + 1);
        state.setFailedCallsInARow(0);
        // This session was told about any change to the spec, so the next one need not be.
        state.setSpecChange(null);
        state.setTakeover(null);
        // Same reason: a turn that ran is proof the vendor is answering again, and a stale
        // rate-limit message beside a working project is worse than no message at all.
        state.setLastError(null);
        Handoff.record(run.paths(), state, result);
        // After the handoff is stored, so the digest this decision depends on is the one this
        // turn just wrote rather than the previous session's.
        Conversation.compactIfDue(state, run.spec().compactAfterSessions());
        state.setPhase(Phase.GATING);
    }

    /**
     * The CLI itself failed and left nothing behind.
     *
     * <p>Distinct from the agent working and the gate disagreeing. A repair round represents an
     * attempt to fix something; an invocation that returned in seconds having written nothing
     * attempted nothing, and letting it spend the budget burns every round in under a minute — as
     * an unrecognised rate-limit message once did here, parking two projects in sixteen seconds.
     *
     * <p>Changes on disk are what separates the two. A turn that failed after doing real work still
     * goes to the gate, because the work deserves judging.
     */
    private boolean invocationFailed(AgentResult result) {
        return result.outcome() == AgentOutcome.FAILED
                && !run.workspace().git().changedSince(run.state().getPreTurnCommit());
    }

    /**
     * Waits a little and tries the same phase again, without charging a repair round or a session.
     *
     * <p>Bounded by {@link PersonHold} rather than by either budget: a failed call reached no model,
     * so it is neither. This used to claim the session budget bounded it, but nothing here ever
     * spent a session, and a CLI that failed the same way every time retried every ten minutes for
     * as long as nobody noticed — project 005 did it seven times on a model the account had no
     * credits for. Now the third in a row waits for a person.
     */
    private void backOff(AgentResult result) {
        RunState state = run.state();
        state.setResumePhase(state.getPhase());
        state.setPhase(Phase.SUSPENDED);
        state.setRetryAfter(Instant.now().plus(Duration.ofMinutes(10)));
        String what = "the agent CLI failed without writing anything: " + shortened(result.finalMessage());
        state.setLastError(what);
        PersonHold.failedCall(state, what);
    }

    /** A CLI's message can be a whole transcript; the card needs the gist. */
    private static String shortened(String message) {
        String text = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
