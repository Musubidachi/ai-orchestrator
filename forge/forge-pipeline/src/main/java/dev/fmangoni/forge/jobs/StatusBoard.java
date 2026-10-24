package dev.fmangoni.forge.jobs;

import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.jobs.button.DeployJob;
import dev.fmangoni.forge.jobs.queue.JobQueue;
import dev.fmangoni.forge.jobs.queue.StartRules;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.state.UnreadableState;
import dev.fmangoni.forge.status.JobStatus;
import java.util.List;
import java.util.Map;

/** The dashboard's rows: one {@link JobStatus} per spec, whether or not it has ever run. */
final class StatusBoard {

    private final Projects projects;
    private final StartRules rules;
    private final JobQueue queue;
    private final JobPool pool;

    StatusBoard(Projects projects, StartRules rules, JobQueue queue, JobPool pool) {
        this.projects = projects;
        this.rules = rules;
        this.queue = queue;
        this.pool = pool;
    }

    List<JobStatus> statuses() {
        Wishlist wishlist = projects.wishlist();
        Map<String, Integer> positions = queue.positions(wishlist);
        // Projects first, each followed by the improvements requested against it at any depth, so
        // the list reads the way the work actually relates rather than in raw filename order.
        // Every spec gets a row: the queue will run a spec whether or not this board shows it.
        return wishlist.inFamilyOrder().stream()
                .map(spec -> statusOf(spec, wishlist, positions))
                .toList();
    }

    private JobStatus statusOf(Spec spec, Wishlist wishlist, Map<String, Integer> positions) {
        RunPaths paths = rules.pathsFor(spec, wishlist);
        String parent = spec.parentId().orElse("");
        int place = positions.getOrDefault(spec.id(), 0);
        boolean running = pool.isRunning(spec.id());
        String blocked = running ? null : rules.blockedReason(spec, wishlist);
        try {
            return RunStore.load(paths.stateFile())
                    .map(state -> started(spec, paths, state, running, parent, place, blocked))
                    .orElseGet(() -> JobStatus.queued(spec.id(), spec.title(),
                            spec.maxRepairRounds(), parent, spec.priority(), place, blocked));
        } catch (UnreadableState e) {
            // Its own card, saying so. Throwing here used to fail the whole list, so a single
            // hand-edited file left the dashboard with no cards at all and no hint as to why.
            return JobStatus.queued(spec.id(), spec.title(), spec.maxRepairRounds(), parent,
                    spec.priority(), place, "state.json cannot be read — fix or remove "
                            + paths.stateFile() + " (" + e.getCause().getMessage() + ")");
        }
    }

    private JobStatus started(Spec spec, RunPaths paths, RunState state, boolean running, String parent,
                              int place, String blocked) {
        JobStatus status = JobStatus.of(state, paths, spec.maxRepairRounds(), running, parent,
                spec.priority(), place, blocked, rules.reviewerWait(state));
        String deployBlocked = running ? "it is running" : DeployJob.deployerFor(spec, paths).blockedReason(state);
        return status.withOutward(status.outward().withMarketing(paths).withDeploy(spec, paths, deployBlocked)
                .withGitHub(paths, state.getId(), Publisher.enabled(projects.home().root())));
    }
}
