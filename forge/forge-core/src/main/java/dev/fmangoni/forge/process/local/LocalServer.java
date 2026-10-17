package dev.fmangoni.forge.process.local;

import dev.fmangoni.forge.process.ProcessTree;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The built project, running on this machine for a few minutes so it can be looked at.
 *
 * <p>Forge picks the port and puts it into the spec's {@code serve} command, so it always knows where
 * the app is and never shares a port with another project's server. Only loopback is ever used:
 * probing is something to do to your own build, on your own machine, and nothing here can be
 * pointed anywhere else.
 *
 * <p>Closing stops the whole process tree. A {@code serve} command is typically an npm script that
 * starts a shell that starts node, and stopping only the first of those leaves the server running
 * with the port held — which is the leftover that made later gates fail on "address in use".
 */
public final class LocalServer implements AutoCloseable {

    private final Process process;
    private final ProcessTree tree;
    private final Set<ProcessHandle> seen;
    private final URI base;

    private LocalServer(Process process, ProcessTree tree, Set<ProcessHandle> seen, URI base) {
        this.process = process;
        this.tree = tree;
        this.seen = seen;
        this.base = base;
    }

    /**
     * Starts {@code serveTemplate} with {@code {port}} filled in and waits until something answers.
     *
     * @throws IOException if it exits, or has not opened the port within {@code ready}
     */
    public static LocalServer start(Path repo, String serveTemplate, Path scriptDir, Duration ready)
            throws IOException {
        int port = freePort();
        Path script = ShellScript.write(scriptDir, "serve", serveTemplate.replace("{port}", String.valueOf(port)));
        Path log = scriptDir.resolve("serve.log");
        Files.createDirectories(scriptDir);
        List<String> invocation = ShellScript.invocation(script);
        Process process = new ProcessBuilder(invocation).directory(repo.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
        // Taken while it is alive: what tells this command's children from a stranger's is that
        // they started after it did, and that cannot be read once it has gone.
        ProcessTree tree = ProcessTree.of(process);
        LocalServer server = null;
        // Every process seen under the command, kept for close(): a child whose parent has exited
        // has no link back to this process any more, and would otherwise be left holding the port.
        Set<ProcessHandle> seen = new HashSet<>();
        Instant deadline = Instant.now().plus(ready);
        try {
            while (Instant.now().isBefore(deadline)) {
                seen.addAll(tree.members());
                String host = answering(port);
                if (host != null) {
                    seen.addAll(tree.members());
                    server = new LocalServer(process, tree, seen, URI.create("http://" + host + ":" + port));
                    return server;
                }
                if (!process.isAlive()) {
                    throw new IOException("the serve command exited (" + process.exitValue() + ") before "
                            + "opening port " + port + ": " + tail(log));
                }
                Thread.sleep(250);
            }
            throw new IOException("nothing answered on port " + port + " within " + ready.toSeconds()
                    + "s: " + tail(log));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the app to start", e);
        } finally {
            if (server == null) {
                stop(process, tree, seen);
            }
        }
    }

    /** Where the app is: {@code http://127.0.0.1:port} or {@code http://[::1]:port}. */
    public URI base() {
        return base;
    }

    @Override
    public void close() {
        stop(process, tree, seen);
    }

    private static void stop(Process process, ProcessTree tree, Set<ProcessHandle> seen) {
        seen.addAll(tree.members());
        seen.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** The loopback address the app is listening on, or null. Dev servers often pick only one. */
    private static String answering(int port) {
        for (String host : List.of("127.0.0.1", "[::1]")) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host.replace("[", "").replace("]", ""), port), 200);
                return host;
            } catch (IOException e) {
                // not this one
            }
        }
        return null;
    }

    private static String tail(Path log) {
        try {
            String text = Files.isRegularFile(log) ? Files.readString(log) : "";
            return text.length() <= 600 ? text.strip() : "…" + text.substring(text.length() - 600).strip();
        } catch (IOException e) {
            return "(no log)";
        }
    }
}
