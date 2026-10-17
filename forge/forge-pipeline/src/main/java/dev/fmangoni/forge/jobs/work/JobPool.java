package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.process.ProcessRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The projects in flight: one worker thread each, how many there may be, and how to stop one.
 *
 * <p>Not synchronized itself; {@link JobRunner} holds its lock around every decision to launch.
 */
public final class JobPool {

    /**
     * How many projects may be in flight at once.
     *
     * <p>Higher than the per-vendor agent limit on purpose: a project spends much of a turn in its
     * own gate running tests, holding no agent slot, and capping at the vendor limit would leave a
     * CLI idle throughout. It is still a cap — without one the dashboard could start every project
     * at once, and the surplus would sit blocked on a semaphore holding threads and disk.
     */
    private final int capacity;

    private final Map<String, JobWorker> running = new ConcurrentHashMap<>();

    /**
     * Jobs submitted whose thread has not finished, cleanup included.
     *
     * <p>Not the same as {@link #running}. A project leaves that map at the start of its cleanup —
     * so the slot frees for the next one — and then goes on writing its report and acting on
     * pending feedback. Anything that needs to know the files have stopped changing waits on this.
     */
    private final AtomicInteger executing = new AtomicInteger();

    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "forge-job");
        t.setDaemon(true);
        return t;
    });

    public JobPool(int capacity) {
        this.capacity = capacity;
    }

    public boolean isRunning(String id) {
        return running.containsKey(id);
    }

    public int size() {
        return running.size();
    }

    public boolean full() {
        return running.size() >= capacity;
    }

    /** The ids in flight, as a live view. */
    public Set<String> ids() {
        return running.keySet();
    }

    /** Whether what is running on this project is a job a button started, rather than its pipeline. */
    public boolean runningButtonJob(String id) {
        JobWorker worker = running.get(id);
        return worker != null && !worker.job.isBlank();
    }

    /** Whether every job has finished, including the writing it does after leaving the pool. */
    public boolean settled() {
        return executing.get() == 0 && running.isEmpty();
    }

    /**
     * Runs {@code work} on a thread of its own, then {@code afterLeaving} once its slot is free.
     *
     * <p>The worker is registered here, synchronously, before the thread exists. See {@link JobWorker}.
     */
    public void submit(String id, Consumer<JobWorker> work, Runnable afterLeaving) {
        submit(id, "", work, afterLeaving);
    }

    /** As above, for a job a button started, named so a shutdown knows to wait for it. */
    public void submit(String id, String job, Consumer<JobWorker> work, Runnable afterLeaving) {
        JobWorker worker = new JobWorker(job);
        running.put(id, worker);
        executing.incrementAndGet();
        threads.submit(() -> {
            worker.thread = Thread.currentThread();
            try {
                try {
                    work.accept(worker);
                } finally {
                    running.remove(id, worker);
                    afterLeaving.run();
                }
            } finally {
                executing.decrementAndGet();
            }
        });
    }

    /**
     * Stops a running project.
     *
     * <p>Interrupts the worker and kills the agent process it is waiting on. Whatever the agent
     * wrote stays on disk with {@code turnInFlight} still set, so the next start commits that work
     * and sends it to the gate rather than paying for the turn twice.
     */
    public String stop(String id) {
        JobWorker worker = running.get(id);
        if (worker == null) {
            return "not running";
        }
        worker.stopped = true;
        Thread thread = worker.thread;
        // No thread yet means it was launched and has not begun; the flag stops it at the door.
        if (thread != null) {
            ProcessRegistry.killWhatever(thread);
            thread.interrupt();
        }
        return "stopping";
    }
}
