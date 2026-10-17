package dev.fmangoni.forge.status;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.status.model.StatusBudget;
import dev.fmangoni.forge.status.model.StatusHandoff;
import dev.fmangoni.forge.status.model.StatusIdentity;
import dev.fmangoni.forge.status.model.StatusOutward;
import dev.fmangoni.forge.status.model.StatusProgress;
import dev.fmangoni.forge.status.model.StatusReview;
import java.time.Instant;

/**
 * Everything the dashboard shows about one project, assembled from state on disk.
 *
 * <p>Derived fresh on each poll rather than cached, so a job advanced by a different process — a
 * scheduled {@code forge run}, say — still shows up correctly here.
 *
 * <p>Grouped by the question each part answers: which project, where it is, what it has cost, what
 * its agent said is left, whether anyone else has read it, and whether it could go out. Each group
 * is a record of its own that knows how to build itself. The groups exist for the Java side only:
 * each is {@link JsonUnwrapped}, so the dashboard still receives one flat object and none of its
 * field names changed.
 *
 * <p>Every group is built by name, never positionally. Adding one field to a long positional list
 * means renumbering every argument after it, which was got wrong twice here — once shifting a
 * {@code 0} so that a compaction count arrived as a review state. Both compiled.
 */
public record JobStatus(
        @JsonUnwrapped StatusIdentity identity,
        @JsonUnwrapped StatusProgress progress,
        @JsonUnwrapped StatusBudget budget,
        @JsonUnwrapped StatusHandoff handoff,
        @JsonUnwrapped StatusReview review,
        @JsonUnwrapped StatusOutward outward) {

    /** The same status with its outward group replaced. */
    public JobStatus withOutward(StatusOutward replacement) {
        return new JobStatus(identity, progress, budget, handoff, review, replacement);
    }

    public static JobStatus of(RunState state, RunPaths paths, int maxRepairRounds, boolean running,
                               String parentId, int priority,
                               int queuePosition, String blockedReason, String reviewerWait) {
        Instant now = Instant.now();
        return new JobStatus(
                StatusIdentity.of(state, parentId, priority),
                StatusProgress.of(state, paths, running, queuePosition, blockedReason, now),
                StatusBudget.of(state, paths, maxRepairRounds, now),
                StatusHandoff.of(state, paths),
                StatusReview.of(state, paths, reviewerWait),
                StatusOutward.of(paths));
    }

    /**
     * A project with no state file yet: queued, never started.
     *
     * <p>Every string component is set explicitly rather than left to the builder's default. A
     * {@code null} here reaches the dashboard as JSON {@code null} and renders as the word "null"
     * in a stat, which is how the absence of a field turns into a visible defect.
     */
    public static JobStatus queued(String id, String title, int maxRepairRounds, String parentId,
                                   int priority, int queuePosition, String blockedReason) {
        return new JobStatus(
                StatusIdentity.queued(id, title, parentId, priority),
                StatusProgress.queued(queuePosition, blockedReason),
                StatusBudget.queued(maxRepairRounds),
                StatusHandoff.queued(),
                StatusReview.queued(),
                StatusOutward.none());
    }
}
