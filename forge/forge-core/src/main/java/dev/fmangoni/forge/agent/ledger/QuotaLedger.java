package dev.fmangoni.forge.agent.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.ledger.model.QuotaWindow;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * What each vendor last said about its quota, and when it said the window reopens.
 *
 * <p>Recorded per vendor rather than per project, which is the level the limit actually applies at.
 * Holding it on the project that happened to discover it means the next project assigned to that
 * same vendor starts, fails identically, and suspends — one exhausted window turning into a series
 * of pointless calls.
 *
 * <p>Read from disk on every query rather than cached. A scheduled {@code forge run} and the editor
 * are separate processes hitting the same subscriptions, and the one that discovers a limit is
 * often not the one that needs to respect it.
 */
@RequiredArgsConstructor
public final class QuotaLedger {

    private static final ObjectMapper JSON = Json.mapper();

    private final Path file;

    /** Notes that a vendor reported a limit. Overwrites any earlier record for that vendor. */
    public synchronized void record(AgentKind kind, Instant retryAfter, String message) {
        Map<String, QuotaWindow> all = read();
        all.put(kind.name(), new QuotaWindow(Instant.now(), retryAfter, shorten(message)));
        write(all);
    }

    /** Clears a vendor's record once it has been shown to be working again. */
    public synchronized void clear(AgentKind kind) {
        Map<String, QuotaWindow> all = read();
        if (all.remove(kind.name()) != null) {
            write(all);
        }
    }

    /** When the vendor's window reopens, or empty if it is not known to be closed. */
    public Optional<Instant> blockedUntil(AgentKind kind) {
        QuotaWindow window = read().get(kind.name());
        if (window == null) {
            return Optional.empty();
        }
        if (window.getRetryAfter() == null) {
            // No stated reset. Hold it briefly rather than indefinitely: an unknown wait should
            // slow the queue down, not stop it for good.
            return Optional.of(window.getObservedAt().plusSeconds(900))
                    .filter(until -> until.isAfter(Instant.now()));
        }
        return Optional.of(window.getRetryAfter()).filter(until -> until.isAfter(Instant.now()));
    }

    public boolean isBlocked(AgentKind kind) {
        return blockedUntil(kind).isPresent();
    }

    /** Everything currently known, for the dashboard. Expired records are not reported as blocks. */
    public Map<String, QuotaWindow> current() {
        return read();
    }

    @SuppressWarnings("unchecked")
    private Map<String, QuotaWindow> read() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(JsonFile.read(file),
                    JSON.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, QuotaWindow.class));
        } catch (IOException e) {
            // A corrupt ledger must not stop the run; treating vendors as available is the
            // recoverable direction, since the next call simply rediscovers the limit.
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, QuotaWindow> all) {
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, JSON.writeValueAsString(all));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private String shorten(String message) {
        String text = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }
}
