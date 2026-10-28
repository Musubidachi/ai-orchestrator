package dev.fmangoni.forge.security.session;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the commands an agent ran and the files it wrote out of its CLI's own event stream.
 *
 * <p>Three shapes, one per vendor, checked against Forge's own logs: Claude Code's {@code tool_use}
 * parts (Bash, PowerShell, Write, Edit) with their results arriving later as {@code tool_result}
 * by id; Codex's completed {@code command_execution} and {@code file_change} items; and
 * Antigravity's finished {@code tool} steps ({@code run_command}, {@code write_to_file}). What the
 * agent said and what it read are left out: only what it did is judged.
 *
 * <p>Streams the file line by line, because a project's log reaches tens of megabytes, and skips
 * any line that cannot hold one of those shapes before parsing it.
 */
public final class SessionLog {

    /** Enough of a command's output to find the process ids it printed. */
    private static final int OUTPUT_CAP = 4_000;

    private SessionLog() {
    }

    public static List<AgentAction> read(Path log) throws IOException {
        String name = log.getFileName().toString();
        List<AgentAction> actions = new ArrayList<>();
        Map<String, Integer> awaiting = new HashMap<>();
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(log), decoder))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("{") || !mayHoldAnAction(line)) {
                    continue;
                }
                JsonNode event;
                try {
                    event = Json.mapper().readTree(line);
                } catch (IOException e) {
                    continue;
                }
                fromClaude(event, name, actions, awaiting);
                fromCodex(event, name, actions);
                fromAntigravity(event, name, actions);
            }
        }
        return actions;
    }

    private static boolean mayHoldAnAction(String line) {
        return line.contains("tool_use") || line.contains("tool_result") || line.contains("command_execution")
                || line.contains("file_change") || line.contains("step_update");
    }

    private static void fromClaude(JsonNode event, String log, List<AgentAction> actions,
                                   Map<String, Integer> awaiting) {
        String type = event.path("type").asText();
        if (!"assistant".equals(type) && !"user".equals(type)) {
            return;
        }
        for (JsonNode part : event.path("message").path("content")) {
            String kind = part.path("type").asText();
            JsonNode input = part.path("input");
            if ("tool_use".equals(kind)) {
                switch (part.path("name").asText()) {
                    case "Bash", "PowerShell" -> {
                        awaiting.put(part.path("id").asText(), actions.size());
                        actions.add(new AgentAction(log, false, input.path("command").asText(), ""));
                    }
                    case "Write", "Edit" -> actions.add(new AgentAction(log, true, input.path("file_path").asText(), ""));
                    case "NotebookEdit" -> actions.add(new AgentAction(log, true, input.path("notebook_path").asText(), ""));
                    default -> {
                    }
                }
            } else if ("tool_result".equals(kind)) {
                Integer at = awaiting.remove(part.path("tool_use_id").asText());
                if (at != null) {
                    actions.set(at, actions.get(at).withOutput(capped(textOf(part.path("content")))));
                }
            }
        }
    }

    private static void fromCodex(JsonNode event, String log, List<AgentAction> actions) {
        if (!"item.completed".equals(event.path("type").asText())) {
            return;
        }
        JsonNode item = event.path("item");
        switch (item.path("type").asText()) {
            case "command_execution" -> actions.add(new AgentAction(log, false, item.path("command").asText(),
                    capped(item.path("aggregated_output").asText())));
            case "file_change" -> item.path("changes").forEach(change ->
                    actions.add(new AgentAction(log, true, change.path("path").asText(), "")));
            default -> {
            }
        }
    }

    private static void fromAntigravity(JsonNode event, String log, List<AgentAction> actions) {
        JsonNode step = event.path("step_update");
        if (!"tool".equals(step.path("step_type").asText()) || !"DONE".equals(step.path("state").asText())) {
            return;
        }
        JsonNode info = step.path("tool_info");
        JsonNode parameters = info.path("parameters");
        switch (step.path("tool_name").asText()) {
            case "run_command" -> actions.add(new AgentAction(log, false, parameters.path("CommandLine").asText(),
                    capped(info.path("output").asText())));
            case "write_to_file", "replace_file_content", "multi_replace_file_content" ->
                    actions.add(new AgentAction(log, true, parameters.path("TargetFile").asText(), ""));
            default -> {
            }
        }
    }

    /** A tool result's content is either a string or a list of text parts. */
    private static String textOf(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder out = new StringBuilder();
        content.forEach(part -> out.append(part.path("text").asText()).append('\n'));
        return out.toString();
    }

    private static String capped(String text) {
        return text.length() <= OUTPUT_CAP ? text : text.substring(0, OUTPUT_CAP);
    }
}
