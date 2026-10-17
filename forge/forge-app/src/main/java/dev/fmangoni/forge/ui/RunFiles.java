package dev.fmangoni.forge.ui;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.ui.http.Http;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * Serves the files a run produced: its report, its evidence, its state.
 *
 * <p>Needed because the root context matches every path the API does not, so a link to
 * {@code runs/003/report.html} was quietly answered with the dashboard itself — a page that looks
 * like it loaded, which is a worse failure than a 404.
 */
@RequiredArgsConstructor
public final class RunFiles {

    private static final String PREFIX = "/runs/";

    /** Enough to cover reports, the evidence they embed, and the artefacts a project exports. */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/plain; charset=utf-8"),
            Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            // Playwright records webm; the others are what a hand-rolled capture usually produces.
            Map.entry("webm", "video/webm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("glb", "model/gltf-binary"));

    private final Path runsDir;

    public void handle(HttpExchange exchange) throws IOException {
        Path file = resolve(exchange.getRequestURI().getPath());
        if (file == null) {
            Http.error(exchange, 404, "no such run file");
            return;
        }
        // Directories are listed rather than 404'd: the report links to runs/<id>/logs/ and
        // runs/<id>/repo/, and a dead link there is exactly when someone wants to look inside.
        if (Files.isDirectory(file)) {
            // Names in a listing are the agent's, so the listing is sandboxed like its files.
            sandbox(exchange);
            Http.html(exchange, listing(file, exchange.getRequestURI().getPath()));
            return;
        }
        send(exchange, file);
    }

    /**
     * Maps a URL path to a file, refusing anything outside the runs directory.
     *
     * <p>The traversal check is not theoretical politeness: this process serves whatever it is
     * asked for, and {@code ../../} in a path would otherwise read any file the user can.
     */
    private Path resolve(String urlPath) {
        if (!urlPath.startsWith(PREFIX)) {
            return null;
        }
        String relative = URLDecoder.decode(
                urlPath.substring(PREFIX.length()), StandardCharsets.UTF_8);
        Path candidate = runsDir.resolve(relative).normalize();
        if (!candidate.startsWith(runsDir.normalize()) || !Files.exists(candidate)) {
            return null;
        }
        // Checked again with links resolved. The name can stay inside runs/ while the file does
        // not: a junction an agent makes in its repository — or that npm makes in node_modules —
        // points anywhere, and this server would have read out whatever was on the far side.
        try {
            if (!candidate.toRealPath().startsWith(runsDir.toRealPath())) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return candidate;
    }

    private void send(HttpExchange exchange, Path file) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        // Reports and evidence are rewritten by later runs, so a cached copy would show stale work.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (designPage(file)) {
            sealed(exchange);
        } else if (!forgeWrote(file)) {
            sandbox(exchange);
        }
        // Streamed rather than read whole: these are whatever a project produced, and a recording or
        // a bundled model file read into memory to be served could take the editor down with it.
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    /**
     * The one kind of file here that Forge wrote itself: a project's report.
     *
     * <p>Everything else under {@code runs/} was written by an agent — the repository, evidence,
     * a project's own HTML output — and agents read untrusted text for a living.
     */
    private boolean forgeWrote(Path file) {
        Path relative = runsDir.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        return relative.getNameCount() == 2 && relative.getFileName().toString().equals("report.html");
    }

    /**
     * A mock-up a vendor drew when asked for designs: {@code runs/<id>/design/...}.
     *
     * <p>These are opened in a person's browser beside each other, and they are meant to be still
     * pictures, so they get the tightest treatment of anything served here.
     */
    private boolean designPage(Path file) {
        Path relative = runsDir.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        return relative.getNameCount() >= 3 && relative.getName(1).toString().equals("design");
    }

    /**
     * Serves a design page with no scripts and no way to reach anything.
     *
     * <p>{@code DesignCheck} drops a page that names the network, but a pattern is a list of the
     * ways somebody thought of — the first version missed {@code srcset} and a meta refresh. This
     * does not depend on anyone having thought of them: the browser itself refuses every load the
     * page tries except its own inline styles and inline images.
     */
    private void sealed(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; "
                        + "media-src data:");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    /**
     * Serves a page as though it came from nowhere.
     *
     * <p>This server is also the API, and the API trusts requests from its own origin — that is
     * what {@link RequestGuard} checks. An agent-written page served from here would be that origin:
     * a project's {@code out.html}, an SVG in its repository, anything a prompt-injected agent
     * chose to write. Its scripts could start runs and save specs past every check the guard makes.
     * A sandboxed page gets an opaque origin instead, so its requests arrive as
     * {@code Origin: null} and are refused, while its own scripts still run for anyone viewing a
     * project's output.
     */
    private void sandbox(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "sandbox allow-scripts allow-forms allow-popups allow-downloads");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private String listing(Path dir, String urlPath) throws IOException {
        StringBuilder out = new StringBuilder(
                "<h1>" + Html.escape(urlPath) + "</h1><ul>");
        String base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
        try (var entries = Files.list(dir)) {
            entries.sorted().forEach(entry -> {
                String name = entry.getFileName().toString()
                        + (Files.isDirectory(entry) ? "/" : "");
                // Escaped and URL-encoded: these names were chosen by whatever wrote the files.
                String href = base + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        .replace("+", "%20").replace("%2F", "/");
                out.append("<li><a href=\"").append(Html.escape(href))
                        .append("\">").append(Html.escape(name))
                        .append("</a></li>");
            });
        }
        return out.append("</ul>").toString();
    }
}
