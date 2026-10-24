package dev.fmangoni.forge.jobs.queue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.git.RepoLock;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.Assignment;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.JobWorker;
import dev.fmangoni.forge.jobs.work.LockedElsewhere;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.jobs.work.ReportRefresh;
import dev.fmangoni.forge.pipeline.Orchestrator;
import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Starts a project's pipeline on a worker, and holds it back when Forge itself fails on it.
 *
 * <p>The scheduler decides what to run unattended; this is what both an explicit Start and the
 * auto-run queue use to launch. Both drive the same {@link Orchestrator} over the same state files,
 * so a project started here is indistinguishable afterwards from one the scheduler picked up.
 */
public final class PipelineJobs {

    /** How long a project that broke Forge itself waits before auto-run tries it again. */
    static final Duration COOL_OFF = Duration.ofMinutes(10);

    private final ForgeHome home;
    private final Agents agents;
    private final QuotaLedger quota;
    private final JobPool pool;
    private final StartRules rules;
    private final Projects projects;
    private final Shutdown shutdown;
    private final LockedElsewhere elsewhere;

    /** What follows a project leaving the pool: its report, pending feedback, the next start. */
    private final BiConsumer<String, RunPaths> afterJob;

    public PipelineJobs(ForgeHome home, Agents agents, QuotaLedger quota, JobPool pool, StartRules rules,
                 Projects projects, Shutdown shutdown, LockedElsewhere elsewhere,
                 BiConsumer<String, RunPaths> afterJob) {
        this.home = home;
        this.agents = agents;
        this.quota = quota;
        this.pool = pool;
        this.rules = rules;
        this.projects = projects;
        this.shutdown = shutdown;
        this.elsewhere = elsewhere;
        this.afterJob = afterJob;
    }

    /**
     * Runs a project now, regardless of what the queue would have chosen.
     *
     * <p>Clears any quota wait, which is the point of an override: the caller is saying they know
     * better than the reset time the CLI last reported.
     */
    public String start(String id) {
        if (shutdown.isDraining()) {
            return "Forge is shutting down; start it again once it has restarted";
        }
        if (pool.isRunning(id)) {
            return "already running";
        }
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        Project project = found.get();
        String blocked = rules.blockedReason(project.spec(), project.wishlist());
        if (blocked != null) {
            return blocked;
        }
        if (pool.full()) {
            return "at capacity (" + pool.size() + " in flight); it will start as one finishes";
        }
        RunState state = RunStore.load(project.paths().stateFile())
                .orElseGet(() -> create(project.spec(), project.wishlist()));
        if (state.getPhase().terminal()) {
            return "already " + state.getPhase();
        }
        clearQuotaWait(state);
        return launch(project.spec(), project.wishlist(), true);
    }

    /** Submits the work. {@code overrideQuotaWait} separates an explicit Start from a timed tick. */
    public String launch(Spec spec, Wishlist wishlist, boolean overrideQuotaWait) {
        RunPaths paths = rules.pathsFor(spec, wishlist);
        // A probe, released at once; the job takes the lock for real when it runs. Checked here
        // too so an explicit Start says what is happening instead of "started" and then nothing.
        Optional<RepoLock> probe = RepoLock.tryAcquire(paths);
        if (probe.isEmpty()) {
            return "another Forge process is working in this repository — try again when it stops";
        }
        probe.get().close();
        RunState state = RunStore.load(paths.stateFile()).orElseGet(() -> create(spec, wishlist));
        if (overrideQuotaWait || rules.eligibility().waitingOnTheWrongVendor(state)) {
            clearQuotaWait(state);
        }
        if (overrideQuotaWait && (state.getCoolOffUntil() != null || PersonHold.anythingToRelease(state))) {
            // Pressing Start after a failure is the person saying they fixed it.
            state.setCoolOffUntil(null);
            PersonHold.release(state);
            RunStore.save(paths.stateFile(), state);
        }
        pool.submit(spec.id(), worker -> execute(worker, spec, paths, state, wishlist),
                () -> afterJob.accept(spec.id(), paths));
        return "started";
    }

    private void execute(JobWorker worker, Spec spec, RunPaths paths, RunState state, Wishlist wishlist) {
        String id = spec.id();
        try {
            if (worker.stopped) {
                return;
            }
            Optional<RepoLock> lock = RepoLock.tryAcquire(paths);
            if (lock.isEmpty()) {
                // Another Forge process — the scheduled run, a second editor — is in this repository.
                // Nothing is written: the state file belongs to whoever holds the lock.
                elsewhere.record(id);
                System.out.println("[" + id + "] another Forge process is working in this "
                        + "repository; leaving it alone");
                return;
            }
            try (RepoLock held = lock.get()) {
                // Stops between steps once Forge is draining, so the gap a shutdown waits for
                // cannot be the moment a new agent turn starts.
                RunState after = new Orchestrator(spec, paths, RunStore.latest(paths.stateFile(), state),
                        agents, new Workspace(paths, home.houseRules(), home.skills()), wishlist,
                        ModelTiers.load(home.root()), advanced -> ReportRefresh.write(paths, null),
                        shutdown::isDraining)
                        .advance();
                forgetErrors(paths, after);
                recordTip(paths, after);
            }
        } catch (RuntimeException e) {
            if (worker.stopped) {
                // A stop lands here as an interrupt. The state file is already current: the
                // orchestrator saves after every transition.
                System.out.println("[" + id + "] stopped: " + e.getMessage());
            } else {
                coolOff(id, paths, e);
            }
        }
    }

    /**
     * Holds back a project whose step threw, instead of relaunching it at once.
     *
     * <p>An exception used to be treated exactly like a stop: logged, and the slot freed — after
     * which {@code fillCapacity} ran, found the project eligible in the same phase, and launched it
     * again. A step that fails the same way every time — a git command refusing, a file that cannot
     * be written — was therefore retried as fast as it could fail, for ever, rewriting the report on
     * every pass and never once saying on the card that anything was wrong.
     *
     * <p>Only auto-run waits. An explicit Start is still an override, which is the point of
     * pressing it after fixing whatever the message says.
     *
     * <p>And only a few times. A cool-off slows a deterministic failure down without stopping it:
     * project 004's gate threw on the same handoff field every twelve minutes for three days,
     * re-running its whole acceptance suite each time first. The third in a row now waits for a
     * person instead.
     */
    private void coolOff(String id, RunPaths paths, RuntimeException e) {
        System.err.println("[" + id + "] failed: " + e);
        try {
            RunStore.load(paths.stateFile()).ifPresent(state -> {
                Instant until = Instant.now().plus(COOL_OFF);
                state.setCoolOffUntil(until);
                state.setLastError("Forge hit an error and will try again after "
                        + LocalTime.ofInstant(until, ZoneId.systemDefault()).withNano(0)
                        + ": " + e.getMessage());
                PersonHold.error(state, String.valueOf(e.getMessage()));
                RunStore.save(paths.stateFile(), state);
            });
        } catch (RuntimeException unwritable) {
            // If even the state cannot be written, the loop this prevents is not the problem.
            System.err.println("[" + id + "] could not record the failure: " + unwritable);
        }
    }

    /**
     * Where a finished project's work ends, read before the lock is let go.
     *
     * <p>Here rather than in a step, because a project reaches DONE from more than one of them, and
     * because only here is it certain that nothing else has committed since.
     */
    private void recordTip(RunPaths paths, RunState after) {
        if (after.getPhase() != Phase.DONE) {
            return;
        }
        String head = new Git(paths.repo(), paths.gitLog()).head();
        if (!head.equals(after.getFinishedCommit())) {
            after.setFinishedCommit(head);
            RunStore.save(paths.stateFile(), after);
        }
    }

    /** A pass that ended without throwing, which is what the error count is counting towards. */
    private void forgetErrors(RunPaths paths, RunState after) {
        if (after.getErrorsInARow() == 0) {
            return;
        }
        after.setErrorsInARow(0);
        RunStore.save(paths.stateFile(), after);
    }

    private void clearQuotaWait(RunState state) {
        if (state.getPhase() != Phase.SUSPENDED) {
            return;
        }
        state.setRetryAfter(null);
        RunStore.save(RunPaths.under(home.runs(), state.getId()).stateFile(), state);
    }

    /** The same fresh run the scheduler makes, so a run started by hand inherits what its spec says. */
    private RunState create(Spec spec, Wishlist wishlist) {
        return rules.eligibility().create(spec,
                new Assignment(existingStates(wishlist), quota, VendorPolicy.load(home.root())));
    }

    private List<RunState> existingStates(Wishlist wishlist) {
        List<RunState> states = new ArrayList<>();
        for (Spec spec : wishlist.all()) {
            RunStore.survey(RunPaths.under(home.runs(), spec.id()).stateFile()).ifPresent(states::add);
        }
        return states;
    }
}
