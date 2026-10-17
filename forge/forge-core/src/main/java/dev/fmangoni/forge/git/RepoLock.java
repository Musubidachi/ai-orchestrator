package dev.fmangoni.forge.git;

import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * One Forge process at a time in a repository.
 *
 * <p>The editor's auto-run and the scheduled {@code forge run} both start eligible projects, and
 * nothing stopped them choosing the same one: {@code JobRunner} knows what it is running and the
 * scheduler knows what it is running, and neither knows about the other. Two orchestrators on one
 * project means two agents writing the same working tree, two sets of state writes racing, and the
 * subscription paying twice. The README recommends registering the scheduled task, so the only thing
 * that had kept this from happening was that nobody had yet.
 *
 * <p>An operating-system lock rather than a marker file, because the process that holds it can die
 * without cleaning up — killed, crashed, rebooted — and the operating system releases the lock
 * regardless. A marker file left behind would stop the project forever.
 *
 * <p>Held on the repository rather than the run, because an improvement works in its parent's
 * repository: two different run ids, one working tree.
 */
public final class RepoLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private RepoLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Beside the repository, so a run and every improvement to it share one. */
    public static Path fileFor(RunPaths paths) {
        return paths.repo().toAbsolutePath().normalize().resolveSibling("repo.lock");
    }

    /** @return the lock, or empty when another process — or this one — already holds it */
    public static Optional<RepoLock> tryAcquire(RunPaths paths) {
        Path file = fileFor(paths);
        FileChannel channel = null;
        try {
            Files.createDirectories(file.getParent());
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new RepoLock(channel, lock));
        } catch (OverlappingFileLockException e) {
            // Already held inside this JVM. Treated the same: somebody is in the repository.
            closeQuietly(channel);
            return Optional.empty();
        } catch (IOException e) {
            closeQuietly(channel);
            // A lock that cannot even be attempted must not become a lock that is never released;
            // refusing to run is the safe reading of "cannot tell".
            System.err.println("could not lock " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel below releases it too.
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the process exiting releases it.
        }
    }
}
