package dev.fmangoni.forge.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.ui.editor.SpecApi;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.RequestGuard;
import dev.fmangoni.forge.ui.route.JobRoutes;
import dev.fmangoni.forge.ui.route.PolishRoutes;
import dev.fmangoni.forge.ui.route.ReviewRoutes;
import dev.fmangoni.forge.ui.route.SpecRoutes;
import dev.fmangoni.forge.ui.route.SystemRoutes;
import dev.fmangoni.forge.ui.suggest.IdeaSource;
import dev.fmangoni.forge.ui.suggest.Suggester;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;

/**
 * A small local editor for the wishlist, served from the JDK's built-in HTTP server.
 *
 * <p>Hosting the form here rather than as a static page is what lets a draft be checked by the same
 * {@link SpecLoader} the overnight run uses, and lets Save write a real file into {@code wishlist/}.
 *
 * <p>Bound to loopback only. This process writes files and has no authentication, so it must not be
 * reachable from the network — binding to {@code 127.0.0.1} rather than to all interfaces is the
 * first of that protection, and {@link RequestGuard} is the rest.
 *
 * <p>This class only wires the server. The endpoints are in {@link SpecRoutes}, {@link JobRoutes}
 * and {@link SystemRoutes}; {@link Endpoints} registers each as a read or a write.
 */
@RequiredArgsConstructor
public final class UiServer {

    private final Path wishlistDir;

    /** Where finished runs keep their reports, evidence and logs. */
    private final Path runsDir;

    private final int port;

    /** Null when neither CLI is installed; the editor still works, only suggestions are off. */
    private final Suggester suggester;

    /** Null for the same reason; the dashboard then shows state without offering to run anything. */
    private final JobRunner jobs;

    /** Vendor quota state, shown so a stalled queue explains itself. */
    private final QuotaLedger quota;

    public String start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            // Built from the port actually bound, which is not the one asked for when that was 0.
            Endpoints endpoints = new Endpoints(server, new RequestGuard(server.getAddress().getPort()));

            endpoints.read("/", UiServer::servePage);
            // Its own context because the root one matches every path the API does not, which is
            // why a report link previously rendered the dashboard instead of the report.
            endpoints.read("/runs/", new RunFiles(runsDir)::handle);
            new SpecRoutes(new SpecApi(wishlistDir, runsDir), wishlistDir, suggester,
                    IdeaSource.load(wishlistDir.getParent(), runsDir).orElse(null)).register(endpoints);
            new JobRoutes(jobs, runsDir).register(endpoints);
            new ReviewRoutes(jobs).register(endpoints);
            new PolishRoutes(jobs).register(endpoints);
            // Usage is recorded beside the ledger the CLIs write to, the same way the model tiers are found.
            new SystemRoutes(jobs, suggester != null, quota,
                    new UsageLedger(wishlistDir.getParent().resolve("usage.json"))).register(endpoints);

            // A suggestion is a live agent call taking a minute or more. The default executor
            // handles requests on a single thread, so without a pool that one call would freeze
            // every other request -- the sidebar, saving, all of it -- until it finished.
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the editor on port " + port, e);
        }
    }

    private static void servePage(HttpExchange exchange) throws IOException {
        try (InputStream in = UiServer.class.getResourceAsStream("/ui/index.html")) {
            if (in == null) {
                Http.error(exchange, 500, "editor page missing from the jar");
                return;
            }
            Http.html(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
