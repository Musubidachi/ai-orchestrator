package dev.fmangoni.forge.status.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.stream.JsonlEvents;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Reads the last thing an agent actually did, out of its own event stream.
 *
 * <p>A phase says where the pipeline is; this says where the <i>model</i> is, which during a
 * half-hour turn is the only thing that distinguishes working from stuck.
 *
 * <p>Reads the tail of the log rather than the whole file: agent transcripts reach hundreds of
 * megabytes, and this is polled every few seconds by a browser.
 */
@UtilityClass
public class Activity {

    private final ObjectMapper JSON = new ObjectMapper();
    private final int TAIL_BYTES = 64 * 1024;

    /** @return a short description of the newest activity, or empty if there is nothing to show */
    public String latest(Path logDir) {
        String claude = fromLog(logDir.resolve("claude.log"));
        String codex = fromLog(logDir.resolve("codex.log"));

        // Whichever log was written most recently is the turn currently in flight.
        long claudeAt = modified(logDir.resolve("claude.log"));
        long codexAt = modified(logDir.resolve("codex.log"));
        return claudeAt >= codexAt ? orElse(claude, codex) : orElse(codex, claude);
    }

    private String orElse(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private String fromLog(Path log) {
        for (String line : reversed(tail(log))) {
            String described = describe(line);
            if (!described.isEmpty()) {
                return described;
            }
        }
        return "";
    }

    /** Recognises the event shapes both CLIs emit; anything unrecognised yields "". */
    private String describe(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(trimmed);
            return switch (node.path("type").asText()) {
                case "assistant" -> fromClaudeMessage(node.path("message"));
                case "item.completed", "item.started" -> fromCodexItem(node.path("item"));
                case "error" -> "error: " + JsonlEvents.plain(node.path("message").asText());
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private String fromClaudeMessage(JsonNode message) {
        for (JsonNode part : message.path("content")) {
            if ("tool_use".equals(part.path("type").asText())) {
                return tool(part.path("name").asText(), part.path("input"));
            }
            if ("text".equals(part.path("type").asText())) {
                return firstSentence(part.path("text").asText());
            }
        }
        return "";
    }

    /**
     * One Codex item, as what the agent is doing.
     *
     * <p>Checked against codex-cli 0.159.3 and every item type in Forge's own logs. Two of these
     * were wrong for as long as they had been here. A file change carries its paths in a
     * {@code changes} list, not a {@code path} field, so every one read as "editing" and nothing
     * after it. And the commonest item of all, a plugin or MCP tool call, was not recognised, along
     * with sub-agent calls and web searches — so the line a person watches during a half-hour turn
     * showed whatever the agent had last said, however long ago that was.
     */
    private String fromCodexItem(JsonNode item) {
        return switch (item.path("type").asText()) {
            case "command_execution" -> "running " + command(item.path("command").asText());
            case "file_change" -> "editing " + changed(item);
            case "agent_message" -> firstSentence(item.path("text").asText());
            case "reasoning" -> firstSentence(item.path("text").asText());
            case "mcp_tool_call" -> toolCall(item);
            case "collab_tool_call" -> "sub-agent: " + item.path("tool").asText().replace('_', ' ');
            case "web_search" -> item.path("query").asText().isBlank()
                    ? "searching the web"
                    : "searching the web for " + shorten(item.path("query").asText(), 70);
            case "error" -> "warning: " + firstSentence(item.path("message").asText());
            default -> "";
        };
    }

    /** The file a change touches, by name; more than one is said as a count. */
    private String changed(JsonNode item) {
        JsonNode changes = item.path("changes");
        String first = changes.path(0).path("path").asText(item.path("path").asText());
        String name = first.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return changes.size() > 1 ? name + " and " + (changes.size() - 1) + " more" : name;
    }

    /** A tool call by the title the agent gave it when there is one, and by its name otherwise. */
    private String toolCall(JsonNode item) {
        String title = item.path("arguments").path("title").asText();
        String tool = item.path("server").asText() + "." + item.path("tool").asText();
        return title.isBlank() ? "calling " + tool : shorten(title, 80) + " (" + tool + ")";
    }

    /** Names the file or command rather than the tool, which is what a reader actually wants. */
    private String tool(String name, JsonNode input) {
        String subject = switch (name) {
            case "Edit", "Write", "Read", "NotebookEdit" -> input.path("file_path").asText();
            case "Bash" -> command(input.path("command").asText());
            case "Glob", "Grep" -> input.path("pattern").asText();
            default -> "";
        };
        String verb = switch (name) {
            case "Edit" -> "editing";
            case "Write" -> "writing";
            case "Read" -> "reading";
            case "Bash" -> "running";
            case "Glob", "Grep" -> "searching";
            default -> name.toLowerCase(Locale.ROOT);
        };
        return subject.isBlank() ? verb : verb + " " + shorten(subject, 70);
    }

    private String firstSentence(String text) {
        String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            return "";
        }
        int stop = clean.indexOf(". ");
        return shorten(stop > 0 ? clean.substring(0, stop + 1) : clean, 110);
    }

    /** Path-shaped things, which is what a reader is actually looking for in a long command. */
    private final Pattern FILE = Pattern.compile(
            "([\\w.\\-]+[/\\\\])*[\\w.\\-]+\\.(ts|tsx|js|jsx|mjs|cjs|java|py|rb|go|rs|cs|json"
            + "|md|html|css|scss|yml|yaml|xml|sh|bat|ps1|toml|sql|txt)\\b");

    /**
     * A script passed inline rather than as arguments.
     *
     * <p>Only when the program is an interpreter, because {@code -e} means something else almost
     * everywhere else: {@code grep -e "foo" app.ts} is a search, and calling it "a grep snippet"
     * would trade one wrong description for another.
     */
    private final Pattern INLINE = Pattern.compile("\\s-[ce]\\s+[\"']");

    private final Set<String> INTERPRETERS =
            Set.of("python", "node", "perl", "ruby", "bash", "sh", "zsh", "pwsh", "powershell",
                    "deno", "php", "osascript");

    /**
     * A command as something a reader can place.
     *
     * <p>Agents edit files by piping a script into an interpreter, and the first seventy characters
     * of one of those is the script's own preamble. {@code running python - <<'PY' import io p=...}
     * reads as though the project were written in Python — which is how a Node project came to look
     * like one on the dashboard, and the only visible evidence said otherwise.
     *
     * <p>What matters is the file being changed, not the language the patch happens to be written
     * in. So a command carrying a script is named by what it is and what it touches; anything else
     * is shown as typed, because a real command line is already the clearest thing available.
     */
    private String command(String command) {
        String clean = command == null ? "" : command.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            return "";
        }
        String program = program(clean);
        boolean script = clean.contains("<<")
                || (INTERPRETERS.contains(program) && INLINE.matcher(clean).find());
        if (!script) {
            return shorten(clean, 70);
        }
        String snippet = "a " + program + " snippet";
        Matcher file = FILE.matcher(clean);
        return file.find() ? snippet + " on " + file.group() : snippet;
    }

    /** The interpreter's plain name: {@code /usr/bin/python3} is a python snippet. */
    private String program(String command) {
        String first = command.split("\\s+")[0];
        String name = first.substring(Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\')) + 1);
        return name.replaceAll("\\.(exe|cmd|bat)$", "").replaceAll("\\d+(\\.\\d+)*$", "");
    }

    private String shorten(String text, int max) {
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private List<String> reversed(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\R")));
        Collections.reverse(lines);
        return lines;
    }

    private String tail(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long from = Math.max(0, size - TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, TAIL_BYTES));
            channel.position(from).read(buffer);
            return new String(buffer.array(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
