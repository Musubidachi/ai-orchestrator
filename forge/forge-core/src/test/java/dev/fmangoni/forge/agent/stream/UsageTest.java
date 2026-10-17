package dev.fmangoni.forge.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading what a turn cost out of two vendors that say it differently.
 *
 * <p>The samples below are trimmed from real transcripts in {@code runs/}, not invented, because
 * the whole value of this class is that it matches what the CLIs actually emit. A field renamed by
 * a vendor update turns every measurement silently into zero, and a budget built on zeros reads as
 * infinite headroom — so the parsing is pinned here and exercised for real by {@code LiveAgentTest}.
 */
class UsageTest {

    @Test
    @DisplayName("Claude's closing result carries tokens and a costed figure")
    void readsClaude() {
        // One object per line, as JSONL actually is. Wrapping these for readability would make
        // the fixture unparseable in a way the real stream never is.
        String stream = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"total_cost_usd\":5.1292896,\"usage\":{\"input_tokens\":116,"
                + "\"cache_creation_input_tokens\":68697,\"cache_read_input_tokens\":22434798,"
                + "\"output_tokens\":36731}}\n";

        Usage usage = Usage.from(AgentKind.CLAUDE, stream);

        assertEquals(116, usage.inputTokens());
        assertEquals(68697 + 22434798, usage.cachedTokens(), "creation and reads are both cache");
        assertEquals(36731, usage.outputTokens());
        assertEquals(5.1292896, usage.costUsd(), 1e-9);
    }

    @Test
    @DisplayName("Antigravity's result event is read, not mistaken for Codex's shape")
    void readsAntigravity() {
        // Verbatim usage from a 003 review, 2026-09-14. Read as Codex, this was NONE, and the
        // ledger never had an Antigravity entry at all.
        String stream = "{\"event\":\"step_update\",\"step_update\":{\"state\":\"DONE\"}}\n"
                + "{\"event\":\"result\",\"result\":{\"status\":\"SUCCESS\",\"response\":\"{}\","
                + "\"usage\":{\"input_tokens\":43294,\"output_tokens\":30177,"
                + "\"thinking_tokens\":30133,\"cache_read_tokens\":0,\"total_tokens\":73471}}}\n";

        Usage usage = Usage.from(AgentKind.ANTIGRAVITY, stream);

        assertEquals(43294, usage.inputTokens());
        assertEquals(30177, usage.outputTokens(), "thinking is already inside output");
        assertEquals(73471, usage.totalTokens());
    }

    @Test
    @DisplayName("Codex reports tokens on turn.completed, and no cost at all")
    void readsCodex() {
        String stream = "{\"type\":\"thread.started\",\"thread_id\":\"01a0\"}\n"
                + "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":655252,"
                + "\"cached_input_tokens\":590592,\"cache_write_input_tokens\":0,"
                + "\"output_tokens\":2201,\"reasoning_output_tokens\":894}}\n";

        Usage usage = Usage.from(AgentKind.CODEX, stream);

        // Codex counts cached input inside input_tokens; Claude keeps them apart. Normalised to
        // Claude's shape so nothing downstream double-counts a resumed conversation.
        assertEquals(655252 - 590592, usage.inputTokens());
        assertEquals(590592, usage.cachedTokens());
        assertEquals(2201 + 894, usage.outputTokens(), "reasoning tokens are output too");
        assertEquals(0, usage.costUsd(), 1e-9, "Codex does not price its own turns");
    }

    @Test
    @DisplayName("a turn that never completed reports nothing rather than zero-as-a-fact")
    void rateLimitedTurnHasNoUsage() {
        String stream =
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"hit your usage limit\"}}";

        assertTrue(Usage.from(AgentKind.CODEX, stream).isEmpty());
        assertTrue(Usage.from(AgentKind.CLAUDE, "").isEmpty());
        assertTrue(Usage.from(AgentKind.CLAUDE, "not json at all").isEmpty());
    }

    @Test
    @DisplayName("the last turn in a stream wins, not the first")
    void takesTheFinalReport() {
        String stream =
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}\n"
                + "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":900,\"output_tokens\":50}}";

        assertEquals(900, Usage.from(AgentKind.CODEX, stream).inputTokens());
    }

    @Test
    @DisplayName("usage adds up across turns, which is the whole point of recording it")
    void accumulates() {
        Usage first = new Usage(100, 1000, 50, 0.25);
        Usage second = new Usage(200, 2000, 60, 0.75);

        Usage total = first.plus(second);

        assertEquals(300, total.inputTokens());
        assertEquals(3000, total.cachedTokens());
        assertEquals(110, total.outputTokens());
        assertEquals(1.0, total.costUsd(), 1e-9);
        assertEquals(3410, total.totalTokens());
    }
}
