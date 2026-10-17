package dev.fmangoni.forge.agent.stream;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.process.ProcResult;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * What a CLI said in its own voice, as opposed to what the model or the project said through it.
 *
 * <p>A failed call is classified by matching phrases — "hit your usage limit", "not logged in" —
 * and those phrases are only evidence when the CLI wrote them. The stream a CLI prints also carries
 * every word the model wrote and every file it read, so a turn on a project with rate-limiting
 * middleware or a login page that failed for any reason at all used to be read as a quota refusal
 * (blocking the vendor for every project) or as a logged-out CLI. Successful turns were protected
 * from this already; failed ones were not.
 *
 * <p>What counts as the CLI's own words:
 *
 * <ul>
 *   <li>stderr, where the CLIs' loggers write — less what Codex prints under a tool's log record
 *       (see {@link #ownStderr});
 *   <li>any stdout line that is not an event — a CLI that fails before streaming prints plain text;
 *   <li>the events a CLI uses to report failure: Codex's {@code error} and {@code turn.failed},
 *       Claude's {@code result} when {@code is_error}, Antigravity's {@code result} with an error;
 *   <li>the model's last words, but only when it never used a tool. Claude reports "Not logged in"
 *       as a synthetic assistant message, so this cannot be dropped altogether; a turn that never
 *       touched a tool read nothing and built nothing, so there is no project content to mistake.
 * </ul>
 */
@UtilityClass
public class CliWords {

    /** The start of one of Codex's log records: a timestamp, a level, and the logger that wrote it. */
    private final Pattern LOG_RECORD = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s+(?:ERROR|WARN|INFO|DEBUG|TRACE)\\s+(\\S+?):(?:\\s|$)");

    /**
     * Stderr with the tools' own output taken out, leaving what the CLI said for itself.
     *
     * <p>Stderr was taken to be the CLI's voice alone, and for Codex it is not. Its tool router logs
     * each failed tool call, and under the record's first line it prints what the tool produced: a
     * command's output, or — for a patch that did not apply, the commonest record in Forge's logs
     * by far — the lines of the patch itself, which are whatever the model wrote. So a project's
     * own text reaches stderr after all, and matching phrases against it is the mistake this class
     * exists to prevent. The record's first line is kept: it is the CLI's, and it is where a
     * sandbox that cannot start a process says so. Everything up to the next record is dropped.
     *
     * <p>Only after a tool-router record. Other loggers, and CLIs that do not timestamp their
     * stderr at all, are kept whole.
     */
    public String ownStderr(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return "";
        }
        List<String> own = new ArrayList<>();
        boolean underAToolRecord = false;
        // Split keeping the empty tail, and joined the same way, so text with nothing to drop
        // comes back line for line as it went in.
        for (String line : stderr.split("\\R", -1)) {
            Matcher record = LOG_RECORD.matcher(line);
            if (record.find()) {
                underAToolRecord = record.group(1).endsWith("tools::router");
            } else if (underAToolRecord && !line.isEmpty()) {
                continue;
            }
            own.add(line);
        }
        return String.join("\n", own);
    }

    public String of(ProcResult proc) {
        StringBuilder words = new StringBuilder(ownStderr(proc.stderr()));
        boolean usedATool = false;
        String lastSaid = null;
        for (String line : (proc.stdout() == null ? "" : proc.stdout()).split("\\R")) {
            JsonNode event = JsonlEvents.parse(line);
            if (event == null) {
                append(words, line);
                continue;
            }
            usedATool |= toolUse(event);
            String said = assistantText(event);
            if (said != null) {
                lastSaid = said;
            }
            if (reportsFailure(event)) {
                append(words, line);
            }
        }
        if (!usedATool && lastSaid != null) {
            append(words, lastSaid);
        }
        return words.toString();
    }

    private void append(StringBuilder words, String text) {
        if (!text.isBlank()) {
            words.append('\n').append(text);
        }
    }

    private boolean reportsFailure(JsonNode event) {
        String type = event.path("type").asText();
        return "error".equals(type)
                || "turn.failed".equals(type)
                || ("result".equals(type) && event.path("is_error").asBoolean(false))
                || ("result".equals(event.path("event").asText())
                        && !event.path("result").path("error").asText("").isBlank());
    }

    /** A tool call by Claude, or any Codex item that is not the model talking or thinking. */
    private boolean toolUse(JsonNode event) {
        String type = event.path("type").asText();
        if ("assistant".equals(type)) {
            for (JsonNode part : event.path("message").path("content")) {
                if ("tool_use".equals(part.path("type").asText())) {
                    return true;
                }
            }
            return false;
        }
        if (type.startsWith("item.")) {
            String item = event.path("item").path("type").asText();
            return !item.isEmpty() && !"agent_message".equals(item) && !"reasoning".equals(item);
        }
        return false;
    }

    /** The text of a Claude assistant message, or null for any other event. */
    private String assistantText(JsonNode event) {
        if (!"assistant".equals(event.path("type").asText())) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : event.path("message").path("content")) {
            if ("text".equals(part.path("type").asText())) {
                text.append(part.path("text").asText()).append('\n');
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}
