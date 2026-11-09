package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Batches;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Looks at every picture batch every few seconds, on a thread of its own.
 *
 * <p>Each look places what a person asked to have placed, checks each picture the agent saved, and
 * writes the agent's list again. Placing goes first, so a click is not left behind checks. Nothing
 * here belongs to a project's jobs: Stop on a project never reaches a check, and the queue never
 * waits for one.
 */
public final class PictureWatch {

    private static final long EVERY_SECONDS = 5;

    /** One watcher for each runs folder, however many times one is asked for. */
    private static final Map<Path, PictureWatch> STARTED = new ConcurrentHashMap<>();

    /**
     * One thread for every watcher. The editor has one runs folder; a test suite that starts a
     * hundred editors, each with a folder of its own, should not leave a hundred threads behind.
     */
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "forge-pictures");
        thread.setDaemon(true);
        return thread;
    });

    private final Path runsDir;

    /** What went wrong with each batch last time, so the same problem is reported once. */
    private final Map<Path, String> problems = new ConcurrentHashMap<>();

    public PictureWatch(Path runsDir) {
        this.runsDir = runsDir;
    }

    /** Starts watching the runs folder, unless that is already being done. */
    public static void start(Path runsDir) {
        STARTED.computeIfAbsent(runsDir.toAbsolutePath().normalize(), dir -> {
            PictureWatch watch = new PictureWatch(dir);
            TICKER.scheduleWithFixedDelay(watch::pass, EVERY_SECONDS, EVERY_SECONDS, TimeUnit.SECONDS);
            return watch;
        });
    }

    /** One look at every batch. */
    public void pass() {
        try {
            for (Path folder : Batches.folders(runsDir)) {
                look(folder);
            }
        } catch (RuntimeException e) {
            report(runsDir, e);
        }
    }

    private void look(Path folder) {
        try {
            Batch batch = Batches.load(folder);
            RecordStore store = new RecordStore(folder);
            Placing.run(batch, store);
            DraftChecks.run(batch, store);
            NextJobs.write(batch);
            problems.remove(folder);
        } catch (RuntimeException e) {
            report(folder, e);
        }
    }

    private void report(Path where, RuntimeException e) {
        String problem = e.getMessage() == null ? e.toString() : e.getMessage();
        if (!problem.equals(problems.put(where, problem))) {
            System.err.println("pictures in " + where.getFileName() + ": " + problem);
        }
    }
}
