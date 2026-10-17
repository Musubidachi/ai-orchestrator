package dev.fmangoni.forge.status.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the dashboard says an agent is doing.
 *
 * <p>This line is the only thing on the card that changes during a half-hour turn, so it is read
 * closely and believed. That makes a misleading one worse than none: a Node project spent a session
 * describing itself as Python, because the agent patched a TypeScript file by piping a Python
 * script into an interpreter and the first seventy characters of that are the script's preamble.
 */
class ActivityTest {

    @TempDir
    Path logs;

    private String claudeRan(String command) throws IOException {
        Files.writeString(logs.resolve("claude.log"),
                """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"Bash","input":{"command":%s}}]}}
                """.formatted(quoted(command)).replace("\n", ""),
                StandardCharsets.UTF_8);
        return Activity.latest(logs);
    }

    @Test
    @DisplayName("a script piped into an interpreter is named by the file it changes")
    void scriptsNameTheirSubject() throws IOException {
        // The command that made a Node project look like a Python one.
        String said = claudeRan("python - <<'PY'\nimport io\np=\"e2e/race.spec.ts\"\n"
                + "s=io.open(p,encoding=\"utf-8\").read()\nPY");
        assertEquals("running a python snippet on e2e/race.spec.ts", said);
        assertFalse(said.contains("import io"), "the script's own body reached the dashboard");
    }

    @Test
    @DisplayName("the interpreter is named plainly, whatever it was invoked as")
    void interpreterNames() throws IOException {
        assertTrue(claudeRan("/usr/bin/python3 - <<'PY'\nprint(1)\nPY").contains("a python snippet"));
        assertTrue(claudeRan("node -e \"require('fs')\"").contains("a node snippet"));
    }

    @Test
    @DisplayName("a script with no file in it still says what it is, rather than quoting itself")
    void scriptsWithoutAFile() throws IOException {
        assertEquals("running a python snippet", claudeRan("python - <<'PY'\nprint(2 + 2)\nPY"));
    }

    @Test
    @DisplayName("an ordinary command is shown as typed, because that is already the clearest thing")
    void plainCommandsAreLeftAlone() throws IOException {
        assertEquals("running npm run build && npm test", claudeRan("npm run build && npm test"));
    }

    @Test
    @DisplayName("the same rule applies to the other CLI's events")
    void codexToo() throws IOException {
        Files.writeString(logs.resolve("codex.log"),
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\","
                        + "\"command\":\"python - <<'PY' open('src/main.ts').read() PY\"}}",
                StandardCharsets.UTF_8);
        assertEquals("running a python snippet on src/main.ts", Activity.latest(logs));
    }

    private String codexDid(String item) throws IOException {
        Files.writeString(logs.resolve("codex.log"),
                "{\"type\":\"item.started\",\"item\":" + item + "}", StandardCharsets.UTF_8);
        return Activity.latest(logs);
    }

    @Test
    @DisplayName("a Codex file change names the file, which lives in a list and not a field")
    void codexFileChangesNameTheFile() throws IOException {
        // Verbatim shape from codex-cli 0.159.3. Read as item.path it said "editing" and stopped.
        assertEquals("editing index.html", codexDid("{\"id\":\"item_1\",\"type\":\"file_change\","
                + "\"changes\":[{\"path\":\"C:\\\\repos\\\\runs\\\\004\\\\repo\\\\index.html\","
                + "\"kind\":\"add\"}],\"status\":\"in_progress\"}"));
        assertEquals("editing a.js and 2 more", codexDid("{\"type\":\"file_change\",\"changes\":["
                + "{\"path\":\"src/a.js\"},{\"path\":\"src/b.js\"},{\"path\":\"src/c.js\"}]}"));
    }

    @Test
    @DisplayName("a Codex tool call is shown by the title the agent gave it")
    void codexToolCallsAreShown() throws IOException {
        // The commonest item in Forge's Codex logs by a factor of four, and it showed nothing.
        assertEquals("Check the workspace path (node_repl.js)", codexDid("{\"id\":\"item_7\","
                + "\"type\":\"mcp_tool_call\",\"server\":\"node_repl\",\"tool\":\"js\",\"arguments\":"
                + "{\"code\":\"nodeRepl.cwd\",\"title\":\"Check the workspace path\"},"
                + "\"result\":null,\"error\":null,\"status\":\"in_progress\"}"));
        assertEquals("calling codex_apps.github.search", codexDid("{\"type\":\"mcp_tool_call\","
                + "\"server\":\"codex_apps\",\"tool\":\"github.search\",\"arguments\":{}}"));
    }

    @Test
    @DisplayName("sub-agents, web searches and warnings each say what they are")
    void otherCodexItems() throws IOException {
        assertEquals("sub-agent: spawn agent", codexDid("{\"type\":\"collab_tool_call\","
                + "\"tool\":\"spawn_agent\",\"prompt\":\"Read-only investigation only.\"}"));
        assertEquals("searching the web", codexDid("{\"type\":\"web_search\",\"query\":\"\","
                + "\"action\":{\"type\":\"other\"}}"));
        assertEquals("warning: Model metadata for `x` not found.", codexDid("{\"type\":\"error\","
                + "\"message\":\"Model metadata for `x` not found. Defaulting to fallback metadata.\"}"));
    }

    @Test
    @DisplayName("an upstream error is shown as its sentence, not as the JSON it arrived in")
    void codexErrorsAreUnwrapped() throws IOException {
        // Verbatim from codex-cli 0.159.3 asked for a model the account cannot use.
        Files.writeString(logs.resolve("codex.log"), "{\"type\":\"error\",\"message\":\"{\\\"type\\\":"
                + "\\\"error\\\",\\\"status\\\":400,\\\"error\\\":{\\\"type\\\":\\\"invalid_request_error\\\","
                + "\\\"message\\\":\\\"The 'no-such-model-xyz' model is not supported when using Codex "
                + "with a ChatGPT account.\\\"}}\"}", StandardCharsets.UTF_8);

        assertEquals("error: 400: The 'no-such-model-xyz' model is not supported when using Codex "
                + "with a ChatGPT account.", Activity.latest(logs));
    }

    @Test
    @DisplayName("nothing to report reads as nothing, not as an empty command")
    void quiet() {
        assertEquals("", Activity.latest(logs));
    }

    /** A JSON string literal, with the newlines and quotes a real command is full of. */
    private String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
