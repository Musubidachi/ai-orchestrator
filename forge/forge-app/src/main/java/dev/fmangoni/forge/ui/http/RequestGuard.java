package dev.fmangoni.forge.ui.http;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a request came from the editor itself, before anything acts on it.
 *
 * <p>The editor binds to loopback and has no authentication, and for a long time that was treated
 * as the whole of its protection. It is not. Loopback keeps other machines out; it does nothing
 * about the browser on this one, where every open tab can send requests to
 * {@code http://localhost:7777}. A page on any site could POST a spec with
 * {@code Content-Type: text/plain} — which a browser sends without asking permission first — and
 * the server parsed the body as JSON regardless, wrote the file, and would have started it. Checked
 * on this machine with a throwaway root: a request carrying {@code Origin: https://evil.example}
 * wrote {@code 666-planted.md}, acceptance command {@code calc.exe}, into the wishlist. The gate
 * executes acceptance commands, and the agents run with permission to do anything.
 *
 * <p>Four checks, each closing a different door:
 * <ul>
 *   <li><b>Host</b> must name this server. A hostile domain that resolves to 127.0.0.1 — DNS
 *       rebinding — becomes same-origin as far as the browser is concerned, so the Origin check
 *       alone would let it through, reading responses as well as sending requests.</li>
 *   <li><b>Origin</b>, when present, must be this server. Browsers attach it to every cross-site
 *       POST; its absence means a non-browser client such as {@code quiesce.ps1}, which is on
 *       this machine already and can do anything the editor can.</li>
 *   <li><b>Sec-Fetch-Site</b>, when present, must not say the request came from elsewhere.</li>
 *   <li>Anything that changes something must be a <b>POST with a JSON body</b>. A GET can be fired
 *       by an image tag, and JSON cannot be sent across origins without a preflight this server
 *       never answers — so even a browser that sent no Origin could not get a write through.</li>
 * </ul>
 */
public final class RequestGuard {

    private final Set<String> hosts;
    private final Set<String> origins;

    public RequestGuard(int port) {
        this.hosts = Set.of("localhost:" + port, "127.0.0.1:" + port, "[::1]:" + port);
        this.origins = Set.of("http://localhost:" + port, "http://127.0.0.1:" + port,
                "http://[::1]:" + port);
    }

    /**
     * @param writes whether the endpoint changes anything: files, runs, quota spent
     * @return why it is refused, or empty when it may proceed
     */
    public Optional<String> refuse(String method, String host, String origin, String fetchSite,
                                   String contentType, boolean writes) {
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.of("this editor only answers to localhost");
        }
        if (!writes) {
            // A read from another site is already unreadable to that site — the browser withholds
            // the response — so the only threat to a read is DNS rebinding, and the Host check has
            // just dealt with it. Refusing cross-site reads as well would break nothing an attacker
            // needs and would break a link to a report opened from anywhere else.
            return Optional.empty();
        }
        if (origin != null && !origin.isBlank() && !origins.contains(origin.toLowerCase(Locale.ROOT))) {
            return Optional.of("requests from other sites are not accepted");
        }
        if (fetchSite != null && !fetchSite.isBlank()
                && !fetchSite.equalsIgnoreCase("same-origin") && !fetchSite.equalsIgnoreCase("none")) {
            return Optional.of("requests from other sites are not accepted");
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return Optional.of("this changes something, so it only accepts POST");
        }
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return Optional.of("send JSON, with Content-Type: application/json");
        }
        return Optional.empty();
    }
}
