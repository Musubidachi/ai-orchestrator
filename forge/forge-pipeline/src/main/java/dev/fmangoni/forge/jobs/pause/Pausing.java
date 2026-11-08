package dev.fmangoni.forge.jobs.pause;

import dev.fmangoni.forge.git.RepoLock;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.state.UnreadableState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Stop with a reason: a project stopped, or one not running, held until a person presses Resume Now.
 *
 * <p>A plain Stop only ended the turn. With the queue on, the slot it freed went straight back to
 * the same project, still the most eligible thing there was, so stopping one to try it, or because
 * its art was not ready, lasted until the next fill. A pause sets the state's cool-off to
 * {@link #UNTIL_RESUMED}. Everything that starts projects already waits out a cool-off (the queue,
 * the resume after a restart, the scheduled run), and an explicit Start already clears one, so
 * nothing else had to learn about pausing. Why it was paused is kept beside the state, in a
 * {@link PauseNote}.
 *
 * <p>Called under {@code JobRunner}'s lock, and kept while a running project winds down: the job
 * leaving frees a slot, and the fill that follows waits for the same lock, so it finds the project
 * held instead of starting it again.
 */
public final class Pausing {

    /** The cool-off a pause sets: so far off that only Resume Now ends it. */
    public static final Instant UNTIL_RESUMED = Instant.parse("9999-01-01T00:00:00Z");

    /** How long a running project may take to wind down before the pause gives up and says so. */
    private static final Duration STOPPING = Duration.ofSeconds(30);

    /** A reason is a line on a card, not a document. */
    private static final int LONGEST = 500;

    private static final String HELD = "; the queue leaves it alone until you press Resume Now";

    private final Projects projects;
    private final JobPool pool;

    public Pausing(Projects projects, JobPool pool) {
        this.projects = projects;
        this.pool = pool;
    }

    /**
     * Stops the project if it is running, then holds it, with the reason given.
     *
     * <p>On a project already held it only changes the reason, and keeps the time it was first paused.
     */
    public String pause(String id, String reason) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        Project project = found.get();
        boolean running = pool.isRunning(id);
        String refused = refusal(project.paths(), running);
        if (refused != null) {
            return refused;
        }
        if (running && !stopped(id)) {
            return "still stopping; press Stop again in a moment to keep the queue off it";
        }
        Optional<RepoLock> lock = RepoLock.tryAcquire(project.paths());
        if (lock.isEmpty() && !familyHoldsLock(project)) {
            return "another Forge process is working in this repository — try again when it stops";
        }
        try {
            return hold(project.paths(), tidy(reason), running);
        } finally {
            lock.ifPresent(RepoLock::close);
        }
    }

    /**
     * Lets a held project go, for Resume Now.
     *
     * <p>Before the start itself, which may still have to wait: for a free slot, for quota, for its
     * parent. It then waits like any other project, instead of staying held after the person said go.
     */
    public void lift(String id) {
        projects.find(id).filter(project -> held(project.paths())).ifPresent(project -> {
            Optional<RepoLock> lock = RepoLock.tryAcquire(project.paths());
            if (lock.isEmpty() && !familyHoldsLock(project)) {
                // The start that follows says another process is in the repository.
                return;
            }
            try {
                RunStore.load(project.paths().stateFile()).ifPresent(state -> {
                    state.setCoolOffUntil(null);
                    RunStore.save(project.paths().stateFile(), state);
                });
                PauseNote.forget(project.paths().root());
            } catch (UncheckedIOException e) {
                System.err.println("[" + id + "] could not lift its pause: " + e.getMessage());
            } finally {
                lock.ifPresent(RepoLock::close);
            }
        });
    }

    /** Every held project's note, by id. A held project whose note was lost has an empty one. */
    public Map<String, PauseNote> notes() {
        Wishlist wishlist = projects.wishlist();
        Map<String, PauseNote> notes = new TreeMap<>();
        for (Spec spec : wishlist.all()) {
            RunPaths paths = projects.of(spec, wishlist).paths();
            if (held(paths)) {
                notes.put(spec.id(), PauseNote.load(paths.root()).orElse(new PauseNote("", null)));
            }
        }
        return notes;
    }

    /**
     * Why a project cannot be paused, asked before anything is stopped; null when it can.
     *
     * <p>A running project with no state yet can still be stopped; one that is not running has
     * nothing a pause could hold.
     */
    private static String refusal(RunPaths paths, boolean running) {
        if (!running && !Files.isRegularFile(paths.stateFile())) {
            return "it has not started, so there is nothing to pause";
        }
        return RunStore.survey(paths.stateFile())
                .filter(state -> state.getPhase().terminal())
                .map(state -> "already " + state.getPhase())
                .orElse(null);
    }

    /** Stops the project's job, and waits for it to leave the pool, as long as {@link #STOPPING} allows. */
    private boolean stopped(String id) {
        pool.stop(id);
        long deadline = System.nanoTime() + STOPPING.toNanos();
        while (pool.isRunning(id)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Writes the hold and its note, with the repository's lock held. */
    private static String hold(RunPaths paths, String reason, boolean stopped) {
        RunState state;
        try {
            Optional<RunState> loaded = RunStore.load(paths.stateFile());
            if (loaded.isEmpty()) {
                return "stopped before it had begun, so there is nothing to hold";
            }
            state = loaded.get();
        } catch (UnreadableState e) {
            return (stopped ? "stopped, but " : "") + "its state cannot be read, so it cannot be held: "
                    + e.getMessage();
        }
        if (state.getPhase().terminal()) {
            return "already " + state.getPhase();
        }
        boolean wasHeld = UNTIL_RESUMED.equals(state.getCoolOffUntil());
        Instant since = wasHeld ? PauseNote.load(paths.root()).map(PauseNote::at).filter(Objects::nonNull)
                .orElseGet(Instant::now) : Instant.now();
        if (!wasHeld) {
            state.setCoolOffUntil(UNTIL_RESUMED);
            try {
                RunStore.save(paths.stateFile(), state);
            } catch (UncheckedIOException e) {
                return (stopped ? "stopped, but " : "") + "it could not be held: " + e.getMessage();
            }
        }
        String done = stopped ? "stopped" : wasHeld ? "reason saved" : "paused";
        try {
            new PauseNote(reason, since).save(paths.root());
        } catch (IOException e) {
            return done + ", but its reason could not be saved: " + e.getMessage();
        }
        return wasHeld && !stopped ? done : done + HELD;
    }

    private static boolean held(RunPaths paths) {
        return RunStore.survey(paths.stateFile())
                .map(state -> UNTIL_RESUMED.equals(state.getCoolOffUntil()))
                .orElse(false);
    }

    /**
     * Whether a job in this process, for another project of the family, holds the repository's lock.
     *
     * <p>An improvement works in its parent's repository, so they share a lock, but each keeps its own
     * state: the job running never writes this project's, and while it holds the lock no other
     * process can be in the repository either.
     */
    private boolean familyHoldsLock(Project project) {
        Path lock = RepoLock.fileFor(project.paths());
        Wishlist wishlist = project.wishlist();
        return pool.ids().stream()
                .filter(other -> !other.equals(project.spec().id()))
                .flatMap(other -> wishlist.byId(other).stream())
                .anyMatch(other -> RepoLock.fileFor(projects.of(other, wishlist).paths()).equals(lock));
    }

    private static String tidy(String reason) {
        String text = reason == null ? "" : reason.strip();
        return text.length() <= LONGEST ? text : text.substring(0, LONGEST).strip() + "…";
    }
}
