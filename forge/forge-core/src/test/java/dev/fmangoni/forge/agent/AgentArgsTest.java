package dev.fmangoni.forge.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.vendor.model.ClaudeArgs;
import dev.fmangoni.forge.agent.vendor.model.CodexArgs;
import dev.fmangoni.forge.agent.vendor.model.CodexOptions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the command lines handed to two programs Forge does not control.
 *
 * <p>Both have already been wrong: {@code --max-turns} did not exist in the installed Claude Code,
 * and Codex refused to start without {@code --skip-git-repo-check} because the check is about trust
 * rather than git. Neither failure was visible until a real run burned quota discovering it.
 *
 * <p>These tests cannot tell you a vendor has changed its flags — only the CLI can do that, which is
 * what {@code LiveAgentTest} is for. What they do is make a change to the argument list deliberate:
 * an edit that drops the sandbox flag or stops resuming a session fails here immediately instead of
 * a week later, in a project that quietly started from scratch each session.
 */
class AgentArgsTest {

    private static final Path CWD = Path.of("C:", "runs", "001", "repo");
    private static final Path OUT = Path.of("C:", "runs", "001", "logs", "last.txt");

    /** The value following {@code flag}, or null if the flag is absent. */
    private static String valueAfter(List<String> args, String flag) {
        int at = args.indexOf(flag);
        return at < 0 || at + 1 >= args.size() ? null : args.get(at + 1);
    }

    @Nested
    @DisplayName("Claude")
    class Claude {

        @Test
        @DisplayName("a fresh turn assigns the session id rather than discovering it afterwards")
        void freshTurnAssignsSession() {
            List<String> args = ClaudeArgs.of(false, null, "sess-1", "", null);

            assertEquals("sess-1", valueAfter(args, "--session-id"));
            assertFalse(args.contains("--resume"), "a new session has nothing to resume");
        }

        @Test
        @DisplayName("a continuing turn resumes and does not assign a new id")
        void continuingTurnResumes() {
            List<String> args = ClaudeArgs.of(false, "sess-1", "ignored", "", null);

            assertEquals("sess-1", valueAfter(args, "--resume"));
            assertFalse(args.contains("--session-id"),
                    "passing both would create a session instead of continuing one");
        }

        @Test
        @DisplayName("a build turn bypasses permissions, because nobody is watching to answer")
        void unattendedOperationIsExplicit() {
            List<String> args = ClaudeArgs.of(false, null, "s", "", null);

            assertEquals("bypassPermissions", valueAfter(args, "--permission-mode"));
            assertEquals("stream-json", valueAfter(args, "--output-format"));
            assertTrue(args.contains("-p"));
        }

        @Test
        @DisplayName("no turn inherits the person's own MCP servers")
        void mcpServersAreNotInherited() {
            // Mail, chat and source hosting, callable unasked under bypassPermissions.
            assertTrue(ClaudeArgs.of(false, null, "s", "", null).contains("--strict-mcp-config"));
            assertTrue(ClaudeArgs.of(true, null, "s", "", null).contains("--strict-mcp-config"));
        }

        @Test
        @DisplayName("a read-only turn cannot run anything")
        void reviewTurnsAreRestricted() {
            assertTrue(ClaudeArgs.of(true, null, "s", "", null).contains("--restricted"));
            assertFalse(ClaudeArgs.of(false, null, "s", "", null).contains("--restricted"));
        }

        @Test
        @DisplayName("restricted and bypassPermissions are never sent together")
        void restrictedUsesTheCompatibleNonAskingMode() {
            // The CLI rejects the pair outright — "bypassPermissions not supported in restricted
            // mode", exit 1, no output — and the caller sees an empty reply rather than an error.
            // The earlier version of this test asserted the broken combination, so it pinned the
            // bug in place instead of catching it: a test can only be as right as its expectation.
            List<String> args = ClaudeArgs.of(true, null, "s", "", null);

            assertEquals("dontAsk", valueAfter(args, "--permission-mode"),
                    "a restricted turn still must never stop and wait for a human");
            assertFalse(args.contains("bypassPermissions"));
        }

        @Test
        @DisplayName("the spec's model beats the run-wide default, and blank means neither")
        void modelPrecedence() {
            assertEquals("opus", valueAfter(ClaudeArgs.of(false, null, "s", "opus", "sonnet"), "--model"));
            assertEquals("sonnet", valueAfter(ClaudeArgs.of(false, null, "s", "", "sonnet"), "--model"));
            assertEquals("sonnet", valueAfter(ClaudeArgs.of(false, null, "s", "  ", "sonnet"), "--model"));
            assertFalse(ClaudeArgs.of(false, null, "s", "", null).contains("--model"),
                    "with no choice at either level the CLI keeps its own default");
        }

        @Test
        @DisplayName("no turn-count flag is passed")
        void noTurnCapFlag() {
            // Removed after it turned out not to exist in 2.1.263; the wall-clock cap is the bound.
            assertFalse(ClaudeArgs.of(false, null, "s", "", null).contains("--max-turns"));
        }
    }

    @Nested
    @DisplayName("Codex")
    class Codex {

        @Test
        @DisplayName("a fresh turn sets its own working directory and sandbox")
        void freshTurnScopesItself() {
            List<String> args = CodexArgs.of(CWD, OUT, false, null, "", null);

            assertEquals("exec", args.get(0));
            assertEquals(CWD.toString(), valueAfter(args, "-C"));
            assertEquals("workspace-write", valueAfter(args, "-s"));
            assertEquals(OUT.toString(), valueAfter(args, "-o"));
        }

        @Test
        @DisplayName("a read-only turn gets the read-only sandbox")
        void reviewTurnsCannotWrite() {
            assertEquals("read-only",
                    valueAfter(CodexArgs.of(CWD, OUT, true, null, "", null), "-s"));
        }

        @Test
        @DisplayName("resume is a subcommand, and rejects the options a fresh exec takes")
        void resumeOmitsTheOptionsItRejects() {
            List<String> args = CodexArgs.of(CWD, OUT, false, "thread-9", "", null);

            assertEquals(List.of("exec", "resume"), args.subList(0, 2));
            assertFalse(args.contains("-C"), "resume rejects -C; the process cwd carries it");
            assertFalse(args.contains("-s"), "resume rejects -s");
            // The id is positional and must come last, immediately before the prompt.
            assertEquals(List.of("thread-9", "-"), args.subList(args.size() - 2, args.size()));
        }

        @Test
        @DisplayName("a resumed turn is told its sandbox again, because the session does not keep it")
        void resumeStatesItsSandbox() {
            // codex-cli 0.159.3: resumed with no sandbox given, a turn that had been writing was
            // refused a write in its own workspace. Every turn after a project's first is a resume.
            assertEquals("sandbox_mode=workspace-write",
                    valueAfter(CodexArgs.of(CWD, OUT, false, "thread-9", "", null), "-c"));
            assertEquals("sandbox_mode=read-only",
                    valueAfter(CodexArgs.of(CWD, OUT, true, "thread-9", "", null), "-c"));
        }

        @Test
        @DisplayName("the sandbox and the features to leave out are passed on every turn, fresh or resumed")
        void optionsAreRepeatedEveryTurn() {
            CodexOptions options = new CodexOptions("unelevated", List.of("computer_use", "plugins"));

            for (String resume : new String[] {null, "thread-9"}) {
                List<String> args = CodexArgs.of(CWD, OUT, false, resume, "", null, options);

                assertTrue(args.contains("windows.sandbox=unelevated"), args.toString());
                assertEquals("-c", args.get(args.indexOf("windows.sandbox=unelevated") - 1));
                assertEquals("--disable", args.get(args.indexOf("computer_use") - 1));
                assertEquals("--disable", args.get(args.indexOf("plugins") - 1));
                // Options, then anything positional: Codex takes the session id and the prompt last.
                assertEquals("-", args.getLast());
                assertTrue(args.indexOf("plugins") < args.size() - 2, args.toString());
            }
        }

        @Test
        @DisplayName("an MCP server from the person's own configuration is switched off by name")
        void namedMcpServersAreDisabled() {
            // Disabling the plugin features leaves a server written straight into config.toml.
            CodexOptions options = new CodexOptions(null, List.of(), List.of("node_repl", "bad.name"));

            List<String> args = CodexArgs.of(CWD, OUT, false, "thread-9", "", null, options);

            assertTrue(args.contains("mcp_servers.node_repl.enabled=false"), args.toString());
            assertFalse(args.stream().anyMatch(arg -> arg.contains("bad.name")),
                    "a dotted name would address some other setting entirely");
        }

        @Test
        @DisplayName("with nothing configured, nothing is overridden")
        void noOptionsChangeNothing() {
            List<String> args = CodexArgs.of(CWD, OUT, false, null, "", null, CodexOptions.none());

            assertFalse(args.contains("--disable"));
            assertFalse(args.contains("-c"), "a fresh turn sets its sandbox with -s alone");
        }

        @Test
        @DisplayName("a setting that is not a plain name never reaches the command line")
        void oddValuesAreLeftOff() {
            // These arrive from a hand-edited vendors.json and pass through cmd.exe.
            CodexOptions options = new CodexOptions("unelevated & calc", List.of("apps\" x", "plugins"));

            List<String> args = CodexArgs.of(CWD, OUT, false, null, "", null, options);

            assertFalse(args.stream().anyMatch(arg -> arg.contains("calc") || arg.contains("\"")),
                    args.toString());
            assertEquals("--disable", args.get(args.indexOf("plugins") - 1));
        }

        @Test
        @DisplayName("the trust check is always skipped, because git init does not satisfy it")
        void trustCheckIsAlwaysSkipped() {
            assertTrue(CodexArgs.of(CWD, OUT, false, null, "", null).contains("--skip-git-repo-check"));
            assertTrue(CodexArgs.of(CWD, OUT, false, "t", "", null).contains("--skip-git-repo-check"));
        }

        @Test
        @DisplayName("the prompt is always the last argument, so it can arrive on stdin")
        void promptComesFromStdin() {
            // Windows caps a command line at 32k and briefs are far longer than that.
            assertEquals("-", CodexArgs.of(CWD, OUT, false, null, "", null).getLast());
            assertEquals("-", CodexArgs.of(CWD, OUT, true, "t", "gpt-6-astra", null).getLast());
        }

        @Test
        @DisplayName("the spec's model beats the run-wide default, and blank means neither")
        void modelPrecedence() {
            assertEquals("gpt-6-astra",
                    valueAfter(CodexArgs.of(CWD, OUT, false, null, "gpt-6-astra", "gpt-5.5"), "-m"));
            assertEquals("gpt-5.5",
                    valueAfter(CodexArgs.of(CWD, OUT, false, null, "", "gpt-5.5"), "-m"));
            assertFalse(CodexArgs.of(CWD, OUT, false, null, "", null).contains("-m"));
        }
    }
}
