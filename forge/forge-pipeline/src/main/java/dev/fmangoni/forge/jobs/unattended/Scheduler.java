package dev.fmangoni.forge.jobs.unattended;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.git.RepoLock;
import dev.fmangoni.forge.jobs.work.Assignment;
import dev.fmangoni.forge.jobs.work.Eligibility;
import dev.fmangoni.forge.pipeline.Orchestrator;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.ProjectReport;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.rank.Ranking;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.state.UnreadableState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;

/**
 * Runs the eligible projects concurrently and returns their final states.
 *
 * <p>The pool is deliberately larger than the per-vendor limit. A project holds a vendor slot only
 * while an agent is actually running; it holds none while its gate compiles and tests, which is
 * where much of the wall-clock time goes. Sizing the pool to the vendor limit would leave both CLIs
 * idle whenever the running projects happened to be building.
 */
@RequiredArgsConstructor
public final class Scheduler {

    private final Path runsDir;
    private final Agents agents;
    private final String houseRules;
    private final int workers;

    /**
     * The one place the "may this run?" questions are answered.
     *
     * <p>Shared with {@code JobRunner} rather than reimplemented here. The two used to keep their
     * own copies and drifted apart three separate times — most recently over whether a project
     * waiting on a rate-limited reviewer may run when a different reviewer is free, which the
     * dashboard learned and this did not.
     */
    private Eligibility rules() {
        return new Eligibility(runsDir, agents, quota);
    }

    /** Vendor quota, so the unattended pass sees the same picture the dashboard does. */
    private final QuotaLedger quota;

    /** The model ladder, from which each job takes its planning, building and reviewing models. */
    private final ModelTiers tiers;

    public List<RunState> run(List<Spec> specs) {
        List<ScheduledJob> jobs = eligible(new Wishlist(specs));
        if (jobs.isEmpty()) {
            return List.of();
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, jobs.size()))) {
            return await(jobs.stream().map(job -> pool.submit(advance(job))).toList());
        }
    }

    private Callable<RunState> advance(ScheduledJob job) {
        return () -> {
            // The editor's auto-run may already be in this repository; see RepoLock.
            Optional<RepoLock> lock = RepoLock.tryAcquire(job.paths());
            if (lock.isEmpty()) {
                System.out.println("[" + job.spec().id() + "] skipped: another Forge process is "
                        + "working in this repository");
                return job.state();
            }
            try (RepoLock held = lock.get()) {
                return new Orchestrator(
                        job.spec(), job.paths(),
                        RunStore.latest(job.paths().stateFile(), job.state()), agents,
                        // runsDir is ForgeHome.runs(), so its parent is the Forge root the skills sit in.
                        new Workspace(job.paths(), houseRules, new ForgeHome(runsDir.getParent()).skills()),
                        job.wishlist(), tiers,
                        advanced -> writeReport(job))
                        .advance();
            }
        };
    }

    /**
     * One project failing must not take the others down: an unattended run is judged on how many
     * projects it finished, and an exception in one is recorded on that project alone.
     */
    private List<RunState> await(List<Future<RunState>> futures) {
        List<RunState> results = new ArrayList<>();
        for (Future<RunState> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // The orchestrator persists state after every transition, so whatever the project
                // reached is already on disk; the report reads it from there.
                System.err.println("project failed: " + e.getCause());
            }
        }
        return results;
    }

    private List<ScheduledJob> eligible(Wishlist wishlist) {
        Eligibility rules = rules();
        List<RunState> existing = wishlist.all().stream()
                .map(spec -> RunStore.survey(RunPaths.under(runsDir, spec.id()).stateFile()))
                .flatMap(Optional::stream)
                .toList();

        // The ledger, where this used to pass null — so the unattended pass would hand new work
        // to a vendor the dashboard already knew was out of quota.
        Assignment assignment = new Assignment(
                existing, quota, VendorPolicy.load(runsDir.getParent()));
        List<ScheduledJob> jobs = new ArrayList<>();
        List<String> claimedRepos = new ArrayList<>();

        for (Spec spec : Ranking.runOrder(wishlist)) {
            if (wishlist.isOrphan(spec) || rules.notHere(spec) != null || !rules.parentAccepted(spec)
                    || rules.familyWait(spec) != null) {
                continue;
            }
            // One job per repository per pass: improvements to a project share its working tree,
            // so running them together would have them editing each other's files.
            String owner = wishlist.repoOwner(spec);
            if (claimedRepos.contains(owner)) {
                continue;
            }

            RunPaths paths = rules.pathsFor(spec, wishlist);
            RunState state;
            try {
                state = RunStore.load(paths.stateFile())
                        .orElseGet(() -> rules.create(spec, assignment));
            } catch (UnreadableState e) {
                // Skipped, not recreated: a fresh run would be written over the project's history.
                System.err.println("[" + spec.id() + "] skipped — " + e.getMessage());
                continue;
            }
            if (rules.ready(state)) {
                claimedRepos.add(owner);
                jobs.add(new ScheduledJob(spec, paths, state, wishlist));
            }
        }
        // Reviews before builds, for the same reason the dashboard does it: a review is one
        // read-only turn that finishes a project, a build turn is one of a dozen that does not.
        // With a lopsided pair of subscriptions every review falls on the same vendor, and letting
        // its window go on building leaves finished projects queued behind unfinished ones.
        jobs.sort(Comparator.comparingInt(
                job -> job.state().getPhase() == Phase.REVIEWING
                        || job.state().getResumePhase() == Phase.REVIEWING ? 0 : 1));
        return jobs;
    }

    /** Keeps each project's report current as it runs, not only once the whole pass finishes. */
    private void writeReport(ScheduledJob job) {
        try {
            RunStore.load(job.paths().stateFile()).ifPresent(state ->
                    new ProjectReport(job.paths(), state,
                            ReviewStore.load(job.paths().reviewFile()))
                            .write());
        } catch (RuntimeException e) {
            System.err.println("could not write the report for " + job.spec().id() + ": " + e.getMessage());
        }
    }





}
