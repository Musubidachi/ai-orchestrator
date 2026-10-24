package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.git.RepoLock;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.ReportRefresh;
import dev.fmangoni.forge.project.RunPaths;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Runs a job a person started with a button: deploy, marketing kit, security check, design options.
 *
 * <p>Each is one piece of work outside the pipeline. It holds a slot in the pool so nothing else
 * starts on the project meanwhile, and it rewrites the report when it ends, whatever happened.
 */
@UtilityClass
public class ButtonJob {

    /**
     * Submits {@code body}, logging how it failed if it does.
     *
     * <p>The pool keeps whatever a task throws and nobody asks for it, so without the logging an
     * Error ended the job with no report and no line anywhere saying why.
     */
    public void submit(JobPool pool, String id, String what, RunPaths paths, Runnable body) {
        pool.submit(id, what, worker -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                System.err.println("[" + id + "] " + what + " failed: " + e.getMessage());
            } catch (Error e) {
                System.err.println("[" + id + "] " + what + " failed: " + e);
                throw e;
            }
        }, () -> ReportRefresh.write(paths, null));
    }

    /** Runs {@code body} holding the repository lock, or says it was skipped when another process has it. */
    public void locked(String id, String what, RunPaths paths, Runnable body) {
        Optional<RepoLock> lock = RepoLock.tryAcquire(paths);
        if (lock.isEmpty()) {
            System.out.println("[" + id + "] " + what + " skipped: another Forge process is in this repository");
            return;
        }
        try (RepoLock held = lock.get()) {
            body.run();
        }
    }
}
