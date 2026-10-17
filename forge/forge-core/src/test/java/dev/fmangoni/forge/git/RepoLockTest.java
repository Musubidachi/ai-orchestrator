package dev.fmangoni.forge.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two Forge processes and one repository.
 *
 * <p>The editor's auto-run and the scheduled {@code forge run} both start eligible projects, and each
 * only knew what it was running itself. The holder here is a real second JVM, because a lock taken
 * inside the same process behaves differently and would prove nothing about the case that matters.
 */
class RepoLockTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a repository another process is working in cannot be taken")
    void anotherProcessHoldsIt() throws Exception {
        RunPaths paths = RunPaths.under(root.resolve("runs"), "001");
        Process holder = LockHolder.holdInAnotherProcess(root, paths);
        try {
            assertTrue(RepoLock.tryAcquire(paths).isEmpty(),
                    "two processes were both allowed into one repository");
        } finally {
            holder.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a holder that dies without cleaning up does not lock the project forever")
    void aKilledHolderReleasesIt() throws Exception {
        // The reason this is an operating-system lock and not a marker file: a marker left by a
        // killed process would stop the project for good.
        RunPaths paths = RunPaths.under(root.resolve("runs"), "001");
        Process holder = LockHolder.holdInAnotherProcess(root, paths);
        holder.destroyForcibly().waitFor(10, TimeUnit.SECONDS);

        try (RepoLock lock = RepoLock.tryAcquire(paths).orElseThrow(
                () -> new AssertionError("a dead process still held the repository"))) {
            assertTrue(Files.exists(RepoLock.fileFor(paths)));
        }
    }

    @Test
    @DisplayName("an improvement shares its parent's lock, because it shares the working tree")
    void improvementsShareTheLock() {
        RunPaths parent = RunPaths.under(root.resolve("runs"), "001");
        RunPaths improvement = RunPaths.under(root.resolve("runs"), "001.1")
                .workingIn(parent.repo());

        assertEquals(RepoLock.fileFor(parent), RepoLock.fileFor(improvement));
    }

    @Test
    @DisplayName("the lock file lives beside the repository, not inside it where git would see it")
    void lockIsOutsideTheWorkingTree() throws IOException {
        RunPaths paths = RunPaths.under(root.resolve("runs"), "001");

        assertEquals(paths.repo().toAbsolutePath().normalize().getParent(),
                RepoLock.fileFor(paths).getParent());
    }
}
