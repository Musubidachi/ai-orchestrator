package dev.fmangoni.forge.agent.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.model.AgentKind;

/**
 * What a turn actually consumed, read from the vendor's own closing event.
 *
 * <p>Worth stating plainly because the opposite was assumed here for a long time: neither CLI
 * reports how much quota is <em>left</em>, but both report exactly what each turn <em>spent</em>.
 * That is the difference between guessing at a budget and measuring one. Consumption is observable;
 * the size of the window is not, and can only be inferred from where the vendor said stop.
 *
 * <p>The two vendors say it differently. Claude puts it in its terminal {@code result} event and
 * includes a costed figure; Codex puts token counts in {@code turn.completed} and no cost. Both are
 * normalised here so the rest of Forge never has to care which one it is talking to.
 *
 * @param inputTokens     fresh input, excluding anything served from cache
 * @param cachedTokens    input read from cache, which is the bulk of a resumed conversation
 * @param outputTokens    everything the model produced, reasoning included where reported
 * @param costUsd         the vendor's own figure, or 0 when it does not give one
 */
public record Usage(long inputTokens, long cachedTokens, long outputTokens, double costUsd) {

    public static final Usage NONE = new Usage(0, 0, 0, 0);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Total tokens moved, which is the closest single number to "how much of the window this was". */
    public long totalTokens() {
        return inputTokens + cachedTokens + outputTokens;
    }

    public Usage plus(Usage other) {
        return new Usage(
                inputTokens + other.inputTokens,
                cachedTokens + other.cachedTokens,
                outputTokens + other.outputTokens,
                costUsd + other.costUsd);
    }

    public boolean isEmpty() {
        return totalTokens() == 0 && costUsd == 0;
    }

    /** Reads whichever shape the stream carries; {@link #NONE} when it carries neither. */
    public static Usage from(AgentKind kind, String stream) {
        return switch (kind) {
            case CLAUDE -> fromClaude(stream);
            case CODEX -> fromCodex(stream);
            case ANTIGRAVITY -> fromAntigravity(stream);
        };
    }

    private static Usage fromClaude(String stream) {
        JsonNode result = lastEventOfType(stream, "result");
        if (result == null) {
            return NONE;
        }
        JsonNode usage = result.path("usage");
        return new Usage(
                usage.path("input_tokens").asLong(),
                usage.path("cache_read_input_tokens").asLong()
                        + usage.path("cache_creation_input_tokens").asLong(),
                usage.path("output_tokens").asLong(),
                result.path("total_cost_usd").asDouble());
    }

    private static Usage fromCodex(String stream) {
        JsonNode turn = lastEventOfType(stream, "turn.completed");
        if (turn == null) {
            return NONE;
        }
        JsonNode usage = turn.path("usage");
        // Codex counts cached input inside input_tokens, so the cached part is subtracted out to
        // match Claude's shape, where the two are already separate.
        long cached = usage.path("cached_input_tokens").asLong();
        return new Usage(
                Math.max(0, usage.path("input_tokens").asLong() - cached),
                cached,
                usage.path("output_tokens").asLong()
                        + usage.path("reasoning_output_tokens").asLong(),
                0);
    }

    /**
     * Antigravity's closing event: {@code {"event":"result","result":{"usage":{...}}}}.
     *
     * <p>It used to be read as Codex's shape, which it is not — no {@code type}, no
     * {@code turn.completed} — so every Antigravity turn recorded nothing. The ledger had no entry
     * for it at all, while it did every review on this machine and ran out of quota four times.
     *
     * <p>Its {@code total_tokens} is input plus output, so cache reads are taken to be part of
     * input, as with Codex. Every turn observed here read nothing from cache, so that is assumed
     * rather than confirmed. Thinking tokens are already inside {@code output_tokens}. No cost.
     */
    private static Usage fromAntigravity(String stream) {
        JsonNode event = lastEvent(stream, "event", "result");
        if (event == null) {
            return NONE;
        }
        JsonNode usage = event.path("result").path("usage");
        long cached = usage.path("cache_read_tokens").asLong();
        return new Usage(
                Math.max(0, usage.path("input_tokens").asLong() - cached),
                cached,
                usage.path("output_tokens").asLong(),
                0);
    }

    private static JsonNode lastEventOfType(String stream, String type) {
        return lastEvent(stream, "type", type);
    }

    private static JsonNode lastEvent(String stream, String field, String type) {
        if (stream == null || stream.isBlank()) {
            return null;
        }
        JsonNode found = null;
        for (String line : stream.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (type.equals(node.path(field).asText())) {
                    found = node;
                }
            } catch (Exception ignored) {
                // The stream is another program's debug channel; an unreadable line is normal.
            }
        }
        return found;
    }
}
