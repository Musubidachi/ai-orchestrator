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
import lombok.RequiredArgsConstructor;

/**
 * What each vendor has been spent on, and what a window turned out to hold.
 *
 * <p>Neither CLI will say how much quota is left. Both say exactly what every turn cost. The
 * difference matters: remaining budget is not readable, but it is <em>derivable</em> — add up what
 * this window has consumed so far, and compare it against what previous windows held before the
 * vendor said stop.
 *
 * <p>So a window's capacity is learned by running out of it. Each time a limit is hit, the running
 * total at that moment is kept as an observation. They are shown as a spread, never averaged into a
 * "remaining" figure: windows here have ended anywhere from 15M to 135M tokens, and a confident
 * wrong number would have projects planning around headroom that does not exist.
 *
 * <p>Deliberately separate from {@link QuotaLedger}. That one holds a fact the vendor stated — it
 * said stop, and named a time. This holds an inference built from measurements. Keeping them apart
 * is what stops the inference being mistaken for the fact.
 */
@RequiredArgsConstructor
public final class UsageLedger {

    private static final ObjectMapper JSON = Json.mapper();

    /** How many past windows to show; enough to see the spread, few enough to follow a change. */
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
     *
     * <p>Unless the last refusal's reset time has not come yet. Then the vendor's window never
     * reset, and what was spent since belongs to the window already recorded. Antigravity keeps a
     * quota per model, so one model can work while another is out: closing a window on each
     * refusal recorded one that "held" 65K tokens, beside others of 4M to 11M.
     *
     * @param resetsAt when the vendor said this window resets, or null if it did not say
     */
    public synchronized void windowExhausted(AgentKind kind, Instant resetsAt) {
        Map<String, UsageWindow> all = read();
        UsageWindow window = all.get(kind.name());
        if (window == null || window.getTurns() == 0) {
            return;
        }
        List<Long> past = new ArrayList<>(window.getPastCapacities());
        long spent = window.spent().totalTokens();
        boolean neverReset = window.getOpensAt() != null && Instant.now().isBefore(window.getOpensAt());
        if (neverReset && !past.isEmpty()) {
            past.set(past.size() - 1, past.getLast() + spent);
        } else {
            past.add(spent);
        }
        while (past.size() > WINDOWS_REMEMBERED) {
            past.removeFirst();
        }
        UsageWindow next = fresh();
        next.setPastCapacities(past);
        next.setOpensAt(resetsAt != null ? resetsAt : neverReset ? window.getOpensAt() : null);
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
