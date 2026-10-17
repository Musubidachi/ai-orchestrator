package dev.fmangoni.forge.jobs.stop;

import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.state.UnreadableState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stops taking new work and waits for the agent turns already under way.
 *
 * <p>Exists because rebuilding Forge meant killing it, and killing it mid-turn orphans whatever the
 * agent has written but not yet had committed. That is not hypothetical: one project here lost a
 * handoff exactly that way, gated on files nobody had reported on, and parked on a complaint about a
 * missing field the turn never got as far as writing. Every rebuild during a working day was a
 * chance to do it again, and there are a lot of rebuilds in a working day.
 *
 * <p>Waits for the turn, not for the project. A project runs until it is done, suspended or parked —
 * potentially hours — but the moment between two turns is perfectly safe to stop in: the state file
 * is written after every transition, the workspace is committed, and the next start picks up
 * exactly where this left off. So the thing to wait for is {@code turnInFlight}, which is the only
 * window where killing the process costs anything.
 *
 * <p>Except for a job a button started. Those have no turns to wait between: a design request, a
 * marketing kit or a deploy runs from start to finish on a project whose phase never changes, so
 * one of those is waited for whole.
 *
 * <p>Never kills anything itself. If the wait runs out it says what is still working and leaves it
 * alone; deciding to stop anyway is a judgement for whoever is at the keyboard, and a shutdown that
 * gives up and starts killing things is the behaviour this replaces.
 */
public final class Shutdown {

    /** The phases whose step calls an agent. A gate is not one: stopping it costs a re-run, no more. */
    private static final Set<Phase> AGENT_PHASES = EnumSet.of(
            Phase.PLANNING, Phase.SCAFFOLDED, Phase.REPAIRING, Phase.CONTINUING,
            Phase.SECURITY_CHECK, Phase.SECURITY_REPAIR, Phase.REVIEWING, Phase.REVIEW_REPAIR);

    private final Projects projects;
    private final JobPool pool;

    /**
     * Set while Forge is trying to stop without destroying anything.
     *
     * <p>Separate from the auto-run setting, and deliberately not persisted. Turning the dashboard
     * setting off to shut down would mean every rebuild quietly disabled the queue, and the next
     * start would sit idle until somebody noticed — which is the kind of off-switch that gets
     * flipped once and then blamed for a week of no progress.
     */
    private volatile boolean draining;

    public Shutdown(Projects projects, JobPool pool) {
        this.projects = projects;
        this.pool = pool;
    }

    public boolean isDraining() {
        return draining;
    }

    /**
     * Drains, and waits up to {@code timeout} for every agent turn to end.
     *
     * @param resume what to do if the wait runs out: the caller was told Forge is still running,
     *               so it has to be running in full. Left draining, it filled no capacity and
     *               refused every Start as "shutting down" until it was restarted by hand.
     */
    public Quiesce quiesce(Duration timeout, Runnable resume) {
        draining = true;
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            List<String> working = midTurn();
            if (working.isEmpty()) {
                return new Quiesce(true, List.of());
            }
            if (Instant.now().isAfter(deadline) || !pause()) {
                draining = false;
                resume.run();
                return new Quiesce(false, working);
            }
        }
    }

    private static boolean pause() {
        try {
            Thread.sleep(2000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The projects whose agent is mid-turn, read from the state files rather than from memory.
     *
     * <p>{@code turnInFlight} is written before the agent is called and cleared after the commit,
     * which makes the file the authority on this and the pool merely the list of candidates to ask
     * about.
     */
    private List<String> midTurn() {
        Wishlist wishlist = projects.wishlist();
        List<String> working = new ArrayList<>();
        for (String id : pool.ids()) {
            if (pool.runningButtonJob(id)) {
                // Design options, a marketing kit, a security check, a deploy: each runs to its end
                // with no step to stop at, and the project's phase says nothing about it — it is
                // DONE or ACCEPTED throughout. Counted only by phase, a stop went straight through
                // the middle of one and killed the vendor calls it was waiting on.
                working.add(id);
                continue;
            }
            Spec spec = wishlist.byId(id).orElse(null);
            if (spec == null) {
                continue;
            }
            try {
                RunStore.load(projects.of(spec, wishlist).paths().stateFile())
                        .filter(Shutdown::busy)
                        .ifPresent(state -> working.add(id));
            } catch (UnreadableState e) {
                // Counted as working. Thrown through, it failed the stop request outright and left
                // Forge draining; skipped, it would let a stop through that nothing had checked.
                System.err.println(e.getMessage());
                working.add(id);
            }
        }
        return working;
    }

    /**
     * Whether stopping now would waste a call to a vendor.
     *
     * <p>{@code turnInFlight} covers the expensive case — an agent writing files that have not been
     * committed — but it is only set around a building turn. A cross-review is an agent call too,
     * and killing one throws away a request against a subscription that is routinely the scarcer of
     * the two. The security check makes an agent call too, and has the app it is probing running.
     *
     * <p>So does planning, and that one is the most expensive call the project will make: it runs on
     * the top of the vendor's ladder. It writes nothing, so {@code turnInFlight} is never set for it
     * and a stop would have gone straight through the middle of it.
     *
     * <p>And a phase whose step is a building turn counts even before {@code turnInFlight} is
     * written. The gap between a gate failing and the repair turn saving that flag is a second or
     * two of git work; a stop that polled into it saw nothing in flight, exited, and killed the turn
     * as it began. The orchestrator now stops between steps while draining, so a project in such a
     * phase either leaves the pool without starting the turn or is genuinely in it — and either
     * way this waits for the answer rather than guessing.
     *
     * <p>Only a project still in the pool is asked, so a phase left behind on disk by a stopped
     * worker holds nothing up.
     */
    private static boolean busy(RunState state) {
        return state.isTurnInFlight() || AGENT_PHASES.contains(state.getPhase());
    }
}
