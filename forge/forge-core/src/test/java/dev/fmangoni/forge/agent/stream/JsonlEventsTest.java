package dev.fmangoni.forge.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The forgiving parser that everything downstream believes.
 *
 * <p>{@code Signals} was well tested; the code extracting the fields {@code Signals} reasons about
 * was not tested at all. That is the wrong way round — a stream that stops parsing does not throw,
 * it returns empty, and empty is indistinguishable from "the agent said nothing". A turn would then
 * be classified on its exit code alone, silently.
 *
 * <p>The inputs below are shaped like the real streams: truncated final lines from a killed
 * process, interleaved plain-text noise, and the {@code subtype: "success"} / {@code is_error: true}
 * contradiction actually observed from Claude Code on this machine.
 */
class JsonlEventsTest {

    @Test
    @DisplayName("the last matching event wins, not the first")
    void takesTheLastMatch() {
        String stream = """
                {"type":"result","result":"first pass"}
                {"type":"result","result":"final answer"}
                """;

        assertEquals(Optional.of("final answer"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("events of other types are ignored")
    void ignoresOtherTypes() {
        String stream = """
                {"type":"assistant","result":"not the summary"}
                {"type":"result","result":"the summary"}
                """;

        assertEquals(Optional.of("the summary"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("a truncated final line does not lose the events before it")
    void survivesTruncation() {
        // What a process killed at its timeout actually leaves behind.
        String stream = """
                {"type":"result","result":"got this far"}
                {"type":"result","resul""";

        assertEquals(Optional.of("got this far"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("plain-text noise between events is skipped")
    void skipsNonJsonLines() {
        String stream = """
                Loading configuration...
                {"type":"result","result":"done"}
                warning: something unrelated
                """;

        assertEquals(Optional.of("done"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("an absent field is empty rather than an empty string")
    void missingFieldIsEmpty() {
        String stream = "{\"type\":\"result\",\"other\":\"x\"}";

        assertTrue(JsonlEvents.lastOf(stream, "result", "result").isEmpty());
    }

    @Test
    @DisplayName("a null field counts as absent, so a later real value is not shadowed")
    void nullFieldIsSkipped() {
        String stream = """
                {"type":"result","result":"real value"}
                {"type":"result","result":null}
                """;

        assertEquals(Optional.of("real value"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("an empty stream is empty, not an exception")
    void emptyStreamIsEmpty() {
        assertTrue(JsonlEvents.lastOf("", "result", "result").isEmpty());
        assertTrue(JsonlEvents.firstError("").isEmpty());
    }

    @Test
    @DisplayName("both line endings are handled, since these streams come from Windows processes")
    void handlesCrlf() {
        String stream = "{\"type\":\"result\",\"result\":\"a\"}\r\n{\"type\":\"result\",\"result\":\"b\"}\r\n";

        assertEquals(Optional.of("b"), JsonlEvents.lastOf(stream, "result", "result"));
    }

    @Test
    @DisplayName("the error message is read from the first error event")
    void findsFirstError() {
        String stream = """
                {"type":"assistant","message":"working"}
                {"type":"error","message":"usage limit reached"}
                {"type":"error","message":"a later one"}
                """;

        assertEquals(Optional.of("usage limit reached"), JsonlEvents.firstError(stream));
    }

    @Test
    @DisplayName("a boolean field is readable as text, which is how is_error is checked")
    void booleansReadAsText() {
        // The contradiction observed for real: a turn that did nothing reported success while
        // is_error was true. Whichever way that is resolved, the flag has to be legible first.
        String stream = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true}";

        assertEquals(Optional.of("success"), JsonlEvents.lastOf(stream, "result", "subtype"));
        assertTrue(Boolean.parseBoolean(
                JsonlEvents.lastOf(stream, "result", "is_error").orElse("false")));
    }

    @Test
    @DisplayName("Codex's session id is read from its opening event")
    void findsCodexThreadId() {
        String stream = """
                {"type":"thread.started","thread_id":"01a087f2-c3d8-7d73-b0c2-8b81e7d7adeb"}
                {"type":"item.completed"}
                """;

        assertEquals(Optional.of("01a087f2-c3d8-7d73-b0c2-8b81e7d7adeb"),
                JsonlEvents.lastOf(stream, "thread.started", "thread_id"));
    }

    @Test
    @DisplayName("an error Codex passes through as JSON is read as the sentence inside it")
    void unwrapsAnUpstreamError() {
        // Verbatim from codex-cli 0.159.3. This whole envelope was the "reason" on a project's card.
        String stream = "{\"type\":\"thread.started\",\"thread_id\":\"01a0f7fd\"}\n"
                + "{\"type\":\"error\",\"message\":\"{\\\"type\\\":\\\"error\\\",\\\"status\\\":400,"
                + "\\\"error\\\":{\\\"type\\\":\\\"invalid_request_error\\\",\\\"message\\\":\\\"The "
                + "'no-such-model-xyz' model is not supported when using Codex with a ChatGPT "
                + "account.\\\"}}\"}";

        assertEquals(Optional.of("400: The 'no-such-model-xyz' model is not supported when using "
                + "Codex with a ChatGPT account."), JsonlEvents.firstError(stream));
    }

    @Test
    @DisplayName("a plain error message is left exactly as it was")
    void plainMessagesAreUntouched() {
        String limit = "You've hit your usage limit. Upgrade to Pro, or try again at 10:00 PM.";

        assertEquals(limit, JsonlEvents.plain(limit));
        assertEquals("{not json", JsonlEvents.plain("{not json"));
        assertEquals("{\"unrelated\":1}", JsonlEvents.plain("{\"unrelated\":1}"));
    }

    @Test
    @DisplayName("a stream with no events at all yields nothing rather than failing")
    void garbageIsSurvivable() {
        String stream = "not json\n[]\n{oops\nnull\n";

        assertTrue(JsonlEvents.lastOf(stream, "result", "result").isEmpty());
        assertFalse(JsonlEvents.firstError(stream).isPresent());
    }
}
