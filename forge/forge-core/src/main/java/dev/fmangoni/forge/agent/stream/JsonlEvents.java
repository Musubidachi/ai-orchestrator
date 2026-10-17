package dev.fmangoni.forge.agent.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Reads the JSONL event streams both CLIs emit.
 *
 * <p>Parsing is deliberately forgiving. The stream is another vendor's debug channel: it interleaves
 * unparseable lines, changes shape between versions, and may be cut off mid-line when a process is
 * killed at its timeout. A malformed line is skipped rather than failing the run, because the
 * authoritative signals Forge acts on are the process exit code and the acceptance gate.
 */
@UtilityClass
public class JsonlEvents {

    private final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Returns the value of {@code field} from the last event whose {@code type} matches.
     *
     * @param type  the event type to look for, e.g. {@code "result"}
     * @param field the field to read from it
     */
    public Optional<String> lastOf(String stream, String type, String field) {
        String found = null;
        for (String line : stream.split("\\R")) {
            JsonNode node = parse(line);
            if (node != null && type.equals(node.path("type").asText()) && node.hasNonNull(field)) {
                found = node.get(field).asText();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * The last event whose {@code discriminator} field equals {@code value}, whole.
     *
     * <p>Two things {@link #lastOf} cannot do. It assumes the discriminator is called {@code type},
     * which is true of Claude and Codex and not of Antigravity — that one calls it {@code event} —
     * and it returns a single field, where an event carrying a nested result object has several
     * worth reading from the same line.
     */
    public Optional<JsonNode> lastWhere(String stream, String discriminator, String value) {
        JsonNode found = null;
        for (String line : stream.split("\\R")) {
            JsonNode node = parse(line);
            if (node != null && value.equals(node.path(discriminator).asText())) {
                found = node;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Collects the messages of every {@code error} event, which is where both CLIs report refusals. */
    public Optional<String> firstError(String stream) {
        for (String line : stream.split("\\R")) {
            JsonNode node = parse(line);
            if (node != null && "error".equals(node.path("type").asText())) {
                return Optional.of(plain(node.path("message").asText(line)));
            }
        }
        return Optional.empty();
    }

    /**
     * An error message as a person would read it.
     *
     * <p>Codex passes an upstream refusal through as JSON inside the message string —
     * {@code {"type":"error","status":400,"error":{"type":"invalid_request_error","message":"The
     * 'gpt-5.4-mini' model is not supported when using Codex with a ChatGPT account."}}} — and that
     * whole envelope was what a project's card showed as the reason it had stopped. Thirty-four of
     * the sixty-eight error events in Forge's logs have this shape; the usage-limit ones are plain
     * text. The inner message is the sentence, with the status in front when there is one.
     */
    public String plain(String message) {
        JsonNode envelope = parse(message == null ? "" : message);
        if (envelope == null) {
            return message;
        }
        String inner = envelope.path("error").path("message").asText(envelope.path("message").asText(""));
        if (inner.isBlank()) {
            return message;
        }
        return envelope.hasNonNull("status") ? envelope.get("status").asText() + ": " + inner : inner;
    }

    /** One line as an event, or null when it is not one. Forgiving, for the reasons above. */
    JsonNode parse(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }
}
