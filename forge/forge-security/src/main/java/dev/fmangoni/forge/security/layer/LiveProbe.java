package dev.fmangoni.forge.security.layer;

import dev.fmangoni.forge.security.layer.model.BodyCheck;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks at the running build the way a curious stranger would, from this machine only.
 *
 * <p>Refuses any address that is not loopback, before sending anything. The probes are ordinary
 * requests — a dotfile, a query string with markup in it — but they are still attempts to make an
 * application misbehave, and the only application they may be aimed at is the one Forge just started.
 *
 * <p>What it can prove is limited, and the report says so: server-side reflection, files served
 * that should not be, and what headers come back. Script that builds the page in the browser is out
 * of its sight — that is left to the AI review, which reads the code.
 */
public final class LiveProbe {

    public static final String LAYER = "live probe";

    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]+src=[\"']([^\"']+\\.js)[\"']");

    private static final Pattern STACK_TRACE = Pattern.compile(
            "Traceback \\(most recent call last\\)|\\tat [a-z][\\w.$]+\\([\\w]+\\.java:\\d+\\)"
                    + "|at Object\\.<anonymous>|Exception in thread|node_modules[\\\\/].+:\\d+:\\d+");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final URI base;
    private final String path;

    /**
     * @throws IllegalArgumentException if {@code base} is not a loopback address
     */
    public LiveProbe(URI base, String path) {
        requireLoopback(base);
        this.base = base;
        this.path = path == null || path.isBlank() ? "/" : path;
    }

    static void requireLoopback(URI base) {
        String host = base.getHost();
        try {
            if (host == null || !InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress()) {
                throw new IllegalArgumentException("refusing to probe " + base + ": only this machine's own build may be probed");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("refusing to probe " + base + ": " + e.getMessage());
        }
    }

    public LayerResult run() {
        List<SecurityFinding> findings = new ArrayList<>();
        HttpResponse<String> page;
        try {
            page = get(path, null);
        } catch (IOException e) {
            return LayerResult.failed(LAYER, "the app did not answer " + path + ": " + e.getMessage());
        }
        headers(page, findings);
        exposedFiles(findings);
        sourceMaps(page.body(), findings);
        reflection(findings);
        errorPages(findings);
        cors(findings);
        return LayerResult.ran(LAYER, "requests to " + base + " only; script that renders in the browser "
                + "is not visible to this probe and is left to the AI review", findings);
    }

    private void headers(HttpResponse<String> page, List<SecurityFinding> findings) {
        var headers = page.headers();
        String csp = headers.firstValue("Content-Security-Policy").orElse("");
        List<String> missing = new ArrayList<>();
        if (csp.isEmpty()) {
            missing.add("Content-Security-Policy");
        }
        if (headers.firstValue("X-Content-Type-Options").isEmpty()) {
            missing.add("X-Content-Type-Options");
        }
        if (headers.firstValue("X-Frame-Options").isEmpty() && !csp.contains("frame-ancestors")) {
            missing.add("X-Frame-Options (or CSP frame-ancestors)");
        }
        if (headers.firstValue("Referrer-Policy").isEmpty()) {
            missing.add("Referrer-Policy");
        }
        if (!missing.isEmpty()) {
            // Low and not serious: a preview server rarely sets these, and it is the production host
            // that must. Recorded so the deploy step has something to check against.
            findings.add(new SecurityFinding(LAYER, SecurityFinding.LOW, "security headers not set",
                    base + path, "missing " + String.join(", ", missing)
                            + " — make sure the host the project is deployed to sets them", false));
        }
    }

    private void exposedFiles(List<SecurityFinding> findings) {
        probeFile("/.git/HEAD", body -> body.startsWith("ref:") || body.matches("(?s)^[0-9a-f]{40}\\s*$"),
                SecurityFinding.HIGH, "the git repository is served", "/.git/HEAD answered with a ref, so the "
                        + "whole history — and anything ever committed to it — can be downloaded", true, findings);
        probeFile("/.env", body -> body.lines().anyMatch(l -> l.matches("(?:export\\s+)?[A-Z][A-Z0-9_]*=.*")),
                SecurityFinding.HIGH, "the .env file is served", "/.env answered with assignments", true, findings);
        probeFile("/package.json", body -> body.contains("\"dependencies\"") || body.contains("\"scripts\""),
                SecurityFinding.LOW, "package.json is served", "the dependency list is public, which tells an "
                        + "attacker exactly which advisories to try", false, findings);
    }

    private void probeFile(String file, BodyCheck check, String severity, String title, String evidence,
                           boolean serious, List<SecurityFinding> findings) {
        try {
            HttpResponse<String> response = get(file, null);
            // An HTML page is never the file: single-page apps answer every unknown path with
            // index.html, and on Pink Slip that page's `content="width=device-width"` passed for an
            // assignment — a served .env reported on a project that has no .env at all.
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            boolean html = type.contains("html") || response.body().stripLeading().startsWith("<");
            if (response.statusCode() == 200 && !html && check.matches(response.body())) {
                findings.add(new SecurityFinding(LAYER, severity, title, base + file, evidence, serious));
            }
        } catch (IOException e) {
            // Unreachable is not exposed.
        }
    }

    private void sourceMaps(String html, List<SecurityFinding> findings) {
        Matcher m = SCRIPT_SRC.matcher(html == null ? "" : html);
        if (!m.find()) {
            return;
        }
        String script = m.group(1).startsWith("/") ? m.group(1) : "/" + m.group(1);
        probeFile(script + ".map", body -> body.contains("\"sources\""), SecurityFinding.LOW,
                "source maps are served", script + ".map returns the original source", false, findings);
    }

    /**
     * Markup sent in a query string and in the path, looked for unescaped in the response.
     *
     * <p>Only a verbatim echo counts as confirmed. The marker is unique to this run, so it cannot be
     * a coincidence, and the tag form only survives if nothing on the way escaped it.
     */
    private void reflection(List<SecurityFinding> findings) {
        String marker = "fp" + UUID.randomUUID().toString().substring(0, 8);
        String payload = marker + "\"'><" + marker + ">";
        String encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8);
        String separator = path.contains("?") ? "&" : "?";
        List<String> attempts = List.of(
                path + separator + "q=" + encoded + "&search=" + encoded + "&name=" + encoded,
                "/" + encoded);
        for (String attempt : attempts) {
            try {
                HttpResponse<String> response = get(attempt, null);
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (response.body().contains("<" + marker + ">") && (type.contains("html") || type.isEmpty())) {
                    findings.add(new SecurityFinding(LAYER, SecurityFinding.HIGH,
                            "input is reflected into the page unescaped (confirmed XSS)", base + attempt,
                            "the tag <" + marker + "> sent in the request came back verbatim in an HTML response", true));
                    return;
                }
            } catch (IOException e) {
                // try the next
            }
        }
    }

    private void errorPages(List<SecurityFinding> findings) {
        for (String attempt : List.of("/%ff%fe", "/forge-probe/..%2f..%2f", path + "?id=%27%22")) {
            try {
                HttpResponse<String> response = get(attempt, null);
                if (response.statusCode() >= 400 && STACK_TRACE.matcher(response.body()).find()) {
                    findings.add(new SecurityFinding(LAYER, SecurityFinding.MEDIUM,
                            "error pages show a stack trace", base + attempt,
                            "HTTP " + response.statusCode() + " with internals in the body", false));
                    return;
                }
            } catch (IOException e) {
                // next
            }
        }
    }

    private void cors(List<SecurityFinding> findings) {
        try {
            HttpResponse<String> response = get(path, "https://forge-probe.invalid");
            String allowed = response.headers().firstValue("Access-Control-Allow-Origin").orElse("");
            boolean credentials = response.headers().firstValue("Access-Control-Allow-Credentials")
                    .map("true"::equalsIgnoreCase).orElse(false);
            if (allowed.equals("https://forge-probe.invalid") && credentials) {
                findings.add(new SecurityFinding(LAYER, SecurityFinding.MEDIUM,
                        "any site may make credentialed requests", base + path,
                        "an arbitrary Origin is echoed back with Access-Control-Allow-Credentials: true", false));
            }
        } catch (IOException e) {
            // nothing to say
        }
    }

    private HttpResponse<String> get(String relative, String origin) throws IOException {
        URI target = URI.create(base + (relative.startsWith("/") ? relative : "/" + relative));
        requireLoopback(target);
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
