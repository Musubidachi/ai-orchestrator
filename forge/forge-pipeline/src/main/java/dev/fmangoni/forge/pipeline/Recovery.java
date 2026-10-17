package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.time.Instant;

/** Puts a run back in order before its next step: the vendor it should use, its phase, lost work. */
final class Recovery {

    private final RunContext run;

    Recovery(RunContext run) {
        this.run = run;
    }

    void prepare() {
        honourExplicitAssignment();
        resumeIfDue();
        recoverInterruptedTurn();
    }

    /**
     * Lets a spec's {@code assign:} still mean something after the run exists.
     *
     * <p>The vendor is chosen once, when the run is created, and stored. That is right for
     * {@code auto} — a balance decision should not flip about between passes — but it silently
     * swallowed the one control a person has over which subscription a project spends. Editing
     * {@code assign: claude} into a spec looked like it did nothing, because nothing ever read it
     * again.
     *
     * <p>Mid-project too, as a takeover. This used to be refused once an agent had worked on the
     * project, because its conversation belongs to that vendor and moving costs it. But some
     * projects cannot be finished where they are: 004 was built by Codex, whose sandbox on this
     * machine cannot launch a browser, so it never saw its own browser tests fail and spent its last
     * sessions on paperwork until the session limit parked it. Only the person changes
     * {@code assign:}, and when they do, they have decided the conversation is worth less than
     * getting the project unstuck. The new vendor starts a conversation of its own, and its first
     * brief says whose work it is taking over and what that means it must check for itself.
     */
    private void honourExplicitAssignment() {
        RunState state = run.state();
        AgentKind named = run.spec().assignedAgent().orElse(null);
        if (named == null || named == state.getAssignedTo()) {
            return;
        }
        AgentKind before = state.getAssignedTo();
        System.out.println("[" + run.spec().id() + "] the spec now says " + named + "; reassigning from " + before);
        state.setAssignedTo(named);
        if (state.getSessions() > 0 && before != null) {
            takeOver(state, before, named);
        }
        RunStore.save(run.paths().stateFile(), state);
    }

    /**
     * Hands a started project to another vendor: no conversation carried over, and a note put in
     * front of the next brief, whatever kind of turn it is (see {@code AgentTurn}).
     */
    private void takeOver(RunState state, AgentKind before, AgentKind named) {
        state.setClaudeSession(null);
        state.setCodexSession(null);
        state.setSessionsOnConversation(0);
        String note = """
                You are taking this project over from %1$s, which built it so far. Its conversation \
                is not carried over, so start from what is on disk: the plan, the code, the tests and \
                the last handoff. Do not trust %1$s's account of what works. Run every acceptance \
                command yourself, including any browser tests, see where the project actually \
                stands, and carry on from there. A failure %1$s reported it could not reproduce is \
                the first thing to look at.""".formatted(before);
        state.setTakeover(note);
    }

    /**
     * A suspended run re-enters the phase it stopped in, once its quota window has reopened.
     *
     * <p>And stops saying why it was suspended. The reason used to stay on the card until the
     * resumed turn finished, so for the half hour that turn ran, a project that was working sat
     * beside "RATE_LIMITED: You've hit your usage limit … try again at 3:57 PM" — at ten past four.
     * It read as a project that should not be running, or one that was stuck and mislabelled, and
     * was neither. If the vendor refuses again the message is written again, with the new time.
     * Saved at once, because the first step after this may be a long one and the dashboard reads
     * the file.
     */
    private void resumeIfDue() {
        RunState state = run.state();
        if (state.getPhase() != Phase.SUSPENDED || state.getResumePhase() == null) {
            return;
        }
        Instant retryAfter = state.getRetryAfter();
        if (retryAfter == null || Instant.now().isAfter(retryAfter)) {
            state.setPhase(state.getResumePhase());
            state.setResumePhase(null);
            state.setRetryAfter(null);
            state.setLastError(null);
            RunStore.save(run.paths().stateFile(), state);
        }
    }

    /**
     * Rescues work left behind by a turn that died before Forge could commit it.
     *
     * <p>An agent turn writes files and only then gets committed, so a crash, reboot or kill in
     * between leaves a finished-looking project with the phase still saying a build is owed. Left
     * alone, the next run would re-issue the original brief and spend another agent turn
     * reproducing what is already on disk — the single most expensive way to lose nothing.
     *
     * <p>The recovery deliberately does not try to judge whether that work is any good. It commits
     * it and sends it to the gate, which is the same answer Forge gives everywhere else: run the
     * acceptance commands and find out, rather than reasoning about it.
     */
    private void recoverInterruptedTurn() {
        RunState state = run.state();
        if (!state.isTurnInFlight()) {
            return;
        }
        run.workspace().git().commitAll("Recovered work from an interrupted " + state.getPhase() + " turn");
        state.setTurnInFlight(false);
        // A model did work, so it is a session — which is also what bounds a project that keeps
        // being interrupted. It is not a repair round: see RunState#recoveredTurn.
        state.setSessions(state.getSessions() + 1);
        state.setRecoveredTurn(true);
        state.setPhase(Phase.GATING);
        RunStore.save(run.paths().stateFile(), state);
    }
}
