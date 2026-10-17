package dev.fmangoni.forge.agent.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.ledger.model.UsageWindow;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.stream.Usage;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * What each vendor has been spent on, and what a window turned out to hold.
 *
 * <p>Neither CLI will say how much quota is left. Both say exactly what every turn cost. The
 * difference matters: remaining budget is not readable, but it is <em>derivable</em> — add up what
 * this window has consumed so far, and compare it against what previous windows held before the
 * vendor said stop.
 *
 * <p>So a window's capacity is learned by running out of it. The first time a limit is hit, the
 * running total at that moment becomes an observation; after a few, there is a usable figure. Until
 * then this reports what has been spent and declines to guess at what is left, because a confident
 * wrong number here would have projects planning around headroom that does not exist.
 *
 * <p>Deliberately separate from {@link QuotaLedger}. That one holds a fact the vendor stated — it
 * said stop, and named a time. This holds an inference built from measurements. Keeping them apart
 * is what stops the inference being mistaken for the fact.
 */
@RequiredArgsConstructor
public final class UsageLedger {

    private static final ObjectMapper JSON = Json.mapper();

    /** How many past windows to average over; enough to smooth one odd window, few enough to adapt. */
    private static final int WINDOWS_REMEMBERED = 5;

    private final Path file;

    /** Adds one turn's consumption to the vendor's current window. */
    public synchronized void record(AgentKind kind, Usage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        Map<String, UsageWindow> all = read();
        UsageWindow window = all.computeIfAbsent(kind.name(), key -> fresh());
        window.setInputTokens(window.getInputTokens() + usage.inputTokens());
        window.setCachedTokens(window.getCachedTokens() + usage.cachedTokens());
        window.setOutputTokens(window.getOutputTokens() + usage.outputTokens());
        window.setCostUsd(window.getCostUsd() + usage.costUsd());
        window.setTurns(window.getTurns() + 1);
        write(all);
    }

    /**
     * Closes the current window, keeping its total as evidence of what one holds.
     *
     * <p>Called when the vendor refuses: whatever had been spent by then is, as closely as this can
     * be known from outside, the size of a window.
     */
    public synchronized void windowExhausted(AgentKind kind) {
        Map<String, UsageWindow> all = read();
        UsageWindow window = all.get(kind.name());
        if (window == null || window.getTurns() == 0) {
            return;
        }
        List<Long> past = new ArrayList<>(window.getPastCapacities());
        past.add(window.spent().totalTokens());
        while (past.size() > WINDOWS_REMEMBERED) {
            past.removeFirst();
        }
        UsageWindow next = fresh();
        next.setPastCapacities(past);
        all.put(kind.name(), next);
        write(all);
    }

    /** What this window has cost so far. */
    public Usage spentThisWindow(AgentKind kind) {
        UsageWindow window = read().get(kind.name());
        return window == null ? Usage.NONE : window.spent();
    }

    public int turnsThisWindow(AgentKind kind) {
        UsageWindow window = read().get(kind.name());
        return window == null ? 0 : window.getTurns();
    }

    /**
     * Tokens a window has held on average, or empty while that is still unknown.
     *
     * <p>Empty is the honest answer until a limit has actually been hit at least once. Nothing
     * should present a budget to an agent before then.
     */
    public Optional<Long> learnedCapacity(AgentKind kind) {
        UsageWindow window = read().get(kind.name());
        if (window == null || window.getPastCapacities().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((long) window.getPastCapacities().stream()
                .mapToLong(Long::longValue).average().orElse(0));
    }

    /** Estimated tokens left in this window, or empty while capacity is still unknown. */
    public Optional<Long> estimatedRemaining(AgentKind kind) {
        return learnedCapacity(kind)
                .map(capacity -> Math.max(0, capacity - spentThisWindow(kind).totalTokens()));
    }

    /** Average tokens a turn has cost in this window, or empty before any turn has run. */
    public Optional<Long> averageTurn(AgentKind kind) {
        int turns = turnsThisWindow(kind);
        return turns == 0
                ? Optional.empty()
                : Optional.of(spentThisWindow(kind).totalTokens() / turns);
    }

    /**
     * Every vendor's current window, as recorded, for display.
     *
     * <p>Handed over as measured rather than summarised into a "remaining" figure. The windows
     * observed here ended at 15M, 134M and 31M tokens — a ninefold spread — so an estimate of what
     * is left would be a confident number built on almost nothing. A person shown the spread can
     * judge that; a gauge would hide it.
     */
    public Map<String, UsageWindow> snapshot() {
        return read();
    }

    private UsageWindow fresh() {
        UsageWindow window = new UsageWindow();
        window.setStartedAt(Instant.now());
        return window;
    }

    private Map<String, UsageWindow> read() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(JsonFile.read(file),
                    JSON.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, UsageWindow.class));
        } catch (IOException e) {
            // Measurements are useful, not load-bearing. Losing them must never stop a build.
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, UsageWindow> all) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(all));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
