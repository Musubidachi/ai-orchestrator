package dev.fmangoni.forge.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session audit, over log lines shaped exactly as each vendor's CLI writes them.
 *
 * <p>The lines are built as JSON objects rather than written out, so a Windows path or a quote in a
 * command reaches the audit as the CLI would have escaped it.
 */
class SessionAuditTest {

    @TempDir
    Path root;

    private Path logs;
    private Path repo;
    private Path temp;
    private final List<String> claude = new ArrayList<>();
    private final List<String> codex = new ArrayList<>();
    private final List<String> antigravity = new ArrayList<>();
    private int ids;

    @BeforeEach
    void folders() throws IOException {
        logs = Files.createDirectories(root.resolve("logs"));
        repo = Files.createDirectories(root.resolve("repo"));
        temp = Files.createDirectories(root.resolve("tmp"));
    }

    // --- log lines, one per vendor shape ---

    private String claudeRan(String command) {
        String id = "toolu_" + (++ids);
        ObjectNode use = Json.mapper().createObjectNode().put("type", "tool_use").put("id", id).put("name", "PowerShell");
        use.putObject("input").put("command", command);
        claude.add(event("assistant", use));
        return id;
    }

    private void claudePrinted(String id, String output) {
        ObjectNode result = Json.mapper().createObjectNode().put("type", "tool_result").put("tool_use_id", id)
                .put("content", output);
        claude.add(event("user", result));
    }

    private void claudeWrote(Path file) {
        ObjectNode use = Json.mapper().createObjectNode().put("type", "tool_use").put("id", "toolu_" + (++ids))
                .put("name", "Write");
        use.putObject("input").put("file_path", file.toString()).put("content", "x");
        claude.add(event("assistant", use));
    }

    private String event(String type, ObjectNode part) {
        ObjectNode event = Json.mapper().createObjectNode().put("type", type);
        event.putObject("message").putArray("content").add(part);
        return event.toString();
    }

    private void codexRan(String command) {
        ObjectNode event = Json.mapper().createObjectNode().put("type", "item.completed");
        event.putObject("item").put("type", "command_execution").put("command", command).put("aggregated_output", "");
        codex.add(event.toString());
    }

    private void codexWrote(Path file) {
        ObjectNode event = Json.mapper().createObjectNode().put("type", "item.completed");
        ObjectNode item = event.putObject("item").put("type", "file_change");
        item.putArray("changes").addObject().put("path", file.toString()).put("kind", "add");
        codex.add(event.toString());
    }

    private void antigravityRan(String command) {
        ObjectNode event = Json.mapper().createObjectNode().put("event", "step_update");
        ObjectNode step = event.putObject("step_update").put("step_type", "tool").put("state", "DONE")
                .put("tool_name", "run_command");
        step.putObject("tool_info").putObject("parameters").put("CommandLine", command);
        antigravity.add(event.toString());
    }

    private LayerResult audit() throws IOException {
        write("claude.log", claude);
        write("codex.log", codex);
        write("antigravity.log", antigravity);
        return new SessionAudit(logs, List.of(repo, root.resolve("design")), temp).run();
    }

    private void write(String name, List<String> lines) throws IOException {
        if (!lines.isEmpty()) {
            // The header Forge writes before each turn is not JSON, and must be skipped.
            List<String> all = new ArrayList<>(List.of("$ cli.cmd -p", "  cwd=" + repo));
            all.addAll(lines);
            Files.write(logs.resolve(name), all, StandardCharsets.UTF_8);
        }
    }

    private static List<String> titles(LayerResult result) {
        return result.findings().stream().map(SecurityFinding::title).toList();
    }

    // --- stopping processes ---

    @Test
    @DisplayName("stopping the process a port lookup found is high, and needs a ruling")
    void stoppingWhatAPortLookupFound() throws IOException {
        // What closed a person's game: look up who holds 4317, then stop that number a minute later.
        String lookup = claudeRan("Get-NetTCPConnection -LocalPort 4317 -State Listen | ForEach-Object { $_.OwningProcess }");
        claudePrinted(lookup, "13:57:21\r\n8200\r\nnode vite preview --port 4317");
        claudeRan("Stop-Process -Id 8200 -Force; Start-Sleep 1");

        LayerResult result = audit();

        assertEquals(List.of("stopped a process it had found holding a port"), titles(result));
        SecurityFinding finding = result.findings().get(0);
        assertEquals(SecurityFinding.HIGH, finding.severity());
        assertFalse(finding.serious(), "nothing can repair what a session did, so no repair turn");
        SecurityReport report = new SecurityReport("c", "t", List.of(result));
        assertEquals(1, report.forRuling().size(), "a high session finding waits for a person");
    }

    @Test
    @DisplayName("stopping in the same command whatever holds a port is high")
    void stoppingByPortInOneCommand() throws IOException {
        claudeRan("Get-NetTCPConnection -LocalPort 4799 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }");

        assertEquals(List.of("stopped whatever held a port"), titles(audit()));
    }

    @Test
    @DisplayName("stopping the server it started a moment ago is not flagged")
    void stoppingItsOwnServer() throws IOException {
        claudeRan("$p = Start-Process cmd.exe -ArgumentList '/c','run.bat' -PassThru; Start-Sleep 6; "
                + "taskkill /PID $p.Id /T /F | Out-Null; (Get-NetTCPConnection -LocalPort 4317 | Measure-Object).Count");

        assertTrue(audit().findings().isEmpty());
    }

    @Test
    @DisplayName("stopping by name is high")
    void stoppingByName() throws IOException {
        codexRan("taskkill /IM node.exe /F");
        claudeRan("Get-Process node | Stop-Process -Force");

        assertEquals(List.of("stopped processes by name", "stopped processes by name"), titles(audit()));
    }

    @Test
    @DisplayName("other stops by number are one medium line per log, with examples, and need no ruling")
    void stopsByNumberAreGrouped() throws IOException {
        claudeRan("Stop-Process -Id 10708,29624 -Force");
        claudeRan("taskkill //PID 33208 //F");

        LayerResult result = audit();

        assertEquals(1, result.findings().size());
        SecurityFinding finding = result.findings().get(0);
        assertEquals(SecurityFinding.MEDIUM, finding.severity());
        assertEquals("claude.log", finding.location());
        assertTrue(finding.evidence().startsWith("2 times"), finding.evidence());
        assertTrue(new SecurityReport("c", "t", List.of(result)).forRuling().isEmpty());
    }

    // --- git, publishing, installing, settings ---

    @Test
    @DisplayName("a push is high, and git stash push is not one")
    void pushes() throws IOException {
        claudeRan("git stash push src/a.ts -q && npx vitest run; git stash pop");
        codexRan("git -C C:/repo push origin main");

        assertEquals(List.of("pushed to a remote"), titles(audit()));
    }

    @Test
    @DisplayName("installing globally, from any vendor, is high")
    void installs() throws IOException {
        antigravityRan("npm install -g typescript");
        claudeRan("npm install --save-dev vitest");

        assertEquals(List.of("installed software outside the project"), titles(audit()));
    }

    @Test
    @DisplayName("changing a system setting is high")
    void systemSettings() throws IOException {
        codexRan("powershell -Command \"Set-ExecutionPolicy Bypass -Scope CurrentUser\"");

        assertEquals(List.of("changed a system setting"), titles(audit()));
    }

    // --- files ---

    @Test
    @DisplayName("writing in the project, a design folder or temp is fine; anywhere else is high")
    void writes() throws IOException {
        claudeWrote(repo.resolve("src/app.ts"));
        codexWrote(root.resolve("design/CODEX/option-1.html"));
        claudeWrote(temp.resolve("claude/scratchpad/patch.py"));
        codexWrote(root.resolve("state.json"));

        LayerResult result = audit();

        assertEquals(List.of("wrote outside the project"), titles(result));
        assertTrue(result.findings().get(0).evidence().contains("state.json"), result.findings().get(0).evidence());
    }

    @Test
    @DisplayName("files written in one folder outside the project are one ruling, not one each")
    void writesInOneFolderAreOneRuling() throws IOException {
        // An agent wrote six helper scripts beside its repository.
        claudeWrote(repo.resolve("../edit1.py"));
        claudeWrote(repo.resolve("../edit2.py"));

        LayerResult result = audit();

        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).evidence().startsWith("2 times"), result.findings().get(0).evidence());
    }

    @Test
    @DisplayName("a note in the vendor's own memory folder is listed, not ruled on")
    void vendorMemoryIsMedium() throws IOException {
        claudeWrote(Path.of("C:/Users/someone/.claude/projects/runs-003-repo/memory/note.md"));

        LayerResult result = audit();

        assertEquals(List.of("used the vendor's own files outside the project"), titles(result));
        assertEquals(SecurityFinding.MEDIUM, result.findings().get(0).severity());
    }

    @Test
    @DisplayName("writing the project's own Claude settings is high, though it is inside the project")
    void projectSettings() throws IOException {
        // Its hooks would run when the next Claude session starts there, outside every sandbox.
        codexWrote(repo.resolve(".claude/settings.json"));

        assertEquals(List.of("touched agent settings or credentials"), titles(audit()));
    }

    @Test
    @DisplayName("deleting outside the project is high; deleting in it or in temp is not")
    void deletes() throws IOException {
        claudeRan("rm -rf dist test-results");
        claudeRan("Remove-Item -Recurse -Force '" + temp.resolve("scratch") + "'");
        claudeRan("Remove-Item -Recurse -Force '" + root.resolve("elsewhere") + "'");

        assertEquals(List.of("deleted files outside the project"), titles(audit()));
    }

    // --- the report ---

    @Test
    @DisplayName("a key on a command line is masked in what the report shows")
    void keysAreMasked() throws IOException {
        String key = "sk" + "-" + "a1b2c3d4e5".repeat(4);
        claudeRan("npm publish --otp " + key);

        SecurityFinding finding = audit().findings().get(0);

        assertEquals("published or deployed", finding.title());
        assertFalse(finding.evidence().contains(key), finding.evidence());
        assertFalse(finding.location().contains(key), finding.location());
    }

    @Test
    @DisplayName("the same command run twice is one finding")
    void repeatsAreOneFinding() throws IOException {
        claudeRan("git push origin main");
        claudeRan("git push origin main");

        assertEquals(1, audit().findings().size());
    }

    @Test
    @DisplayName("with no logs yet, the layer says it was skipped rather than that all is well")
    void noLogs() throws IOException {
        assertEquals(LayerResult.SKIPPED, audit().status());
    }
}
