package dev.fmangoni.forge.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.project.RunPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A second JVM holding a repository's lock, for tests about two Forge processes.
 *
 * <p>A real process, because a lock taken inside the same process behaves differently and would
 * prove nothing about the case that matters. Shared with the job tests through this module's
 * test-jar.
 */
public final class LockHolder {

    private LockHolder() {
    }

    /** Starts another JVM that takes the lock, says so, and holds it until killed. */
    public static Process holdInAnotherProcess(Path scratch, RunPaths paths) throws Exception {
        Path source = scratch.resolve("Holder.java");
        Files.writeString(source, """
                import java.nio.channels.FileChannel;
                import java.nio.file.*;
                public class Holder {
                    public static void main(String[] args) throws Exception {
                        Path file = Path.of(args[0]);
                        Files.createDirectories(file.getParent());
                        FileChannel channel = FileChannel.open(file,
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        channel.lock();
                        System.out.println("held");
                        System.out.flush();
                        Thread.sleep(60_000);
                    }
                }
                """);
        String java = ProcessHandle.current().info().command().orElse("java");
        Process holder = new ProcessBuilder(java, source.toString(),
                RepoLock.fileFor(paths).toString()).redirectErrorStream(true).start();
        byte[] line = new byte[4];
        int read = holder.getInputStream().readNBytes(line, 0, 4);
        assertEquals("held", new String(line, 0, read, StandardCharsets.UTF_8),
                "the holder process did not take the lock");
        return holder;
    }
}
