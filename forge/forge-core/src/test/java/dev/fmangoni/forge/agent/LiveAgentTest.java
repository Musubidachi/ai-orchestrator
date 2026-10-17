package dev.fmangoni.forge.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.vendor.AntigravityAgent;
import dev.fmangoni.forge.agent.vendor.ClaudeAgent;
import dev.fmangoni.forge.agent.vendor.CliLocator;
import dev.fmangoni.forge.agent.vendor.CodexAgent;
import dev.fmangoni.forge.agent.vendor.model.CodexOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves an agent can be driven end to end from Java: prompt in on stdin, real file out on disk.
 *
 * <p>Tagged {@code live} and excluded from the default build — these spend subscription quota and
 * fail when a window is exhausted. Run with {@code mvn test -Dexcluded.groups=}.
 *
 * <p>The assertion is deliberately on the <b>filesystem</b>, not on what the agent said it did.
 * That is the same inversion the whole pipeline rests on, applied to its smallest case.
 */
@Tag("live")
class LiveAgentTest {

    private static final Duration CAP = Duration.ofMinutes(5);

    @TempDir
    Path workspace;

    @Test
    @DisplayName("Claude Code writes a real file when driven headlessly from Java")
    void claudeProducesAFile() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.CLAUDE).isPresent(), "claude CLI not installed");
        assertAgentWritesHelloFile(ClaudeAgent.create(null));
    }

    @Test
    @DisplayName("Codex writes a real file when driven headlessly from Java")
    void codexProducesAFile() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.CODEX).isPresent(), "codex CLI not installed");
        assertAgentWritesHelloFile(codex());
    }

    @Test
    @DisplayName("Claude answers a read-only turn, which is what reviews and suggestions use")
    void claudeAnswersReadOnly() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.CLAUDE).isPresent(), "claude CLI not installed");
        assertAgentAnswersWithoutWriting(ClaudeAgent.create(null));
    }

    @Test
    @DisplayName("Codex answers a read-only turn, which is what reviews and suggestions use")
    void codexAnswersReadOnly() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.CODEX).isPresent(), "codex CLI not installed");
        assertAgentAnswersWithoutWriting(codex());
    }

    /**
     * Codex as Forge starts it: without the features that reach outside a repository, in the
     * sandbox named with {@code -Dforge.codex.sandbox=…} when this machine needs one named, and
     * without the MCP servers listed in {@code -Dforge.codex.mcpOff=a,b}.
     */
    private static Agent codex() {
        String mcpOff = System.getProperty("forge.codex.mcpOff", "");
        return CodexAgent.create(null, new CodexOptions(System.getProperty("forge.codex.sandbox"),
                List.of("computer_use", "browser_use", "apps", "plugins"),
                mcpOff.isBlank() ? List.of() : List.of(mcpOff.split(","))));
    }

    @Test
    @DisplayName("Codex can still write when a session is resumed, which is every turn but the first")
    void codexStillWritesWhenResumed() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.CODEX).isPresent(), "codex CLI not installed");
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        Agent codex = codex();

        AgentResult first = codex.run(AgentTask.writable(workspace,
                "Create a file named first.txt in the current directory whose entire contents are "
                        + "the single word: forge", CAP, logs));
        assumeTrue(!first.blockedExternally(),
                () -> "codex unavailable: " + first.outcome() + " — " + first.finalMessage());
        assertTrue(Files.isRegularFile(workspace.resolve("first.txt")), "the fresh turn wrote nothing");

        // The regression this exists for. On 0.153.4 a resumed session kept the sandbox it was
        // started with; on 0.159.3 it came back read-only unless told otherwise, and a turn asked
        // to write a file in its own workspace was refused. No test that builds an argument list
        // can know which of those the installed CLI does.
        AgentResult resumed = codex.run(AgentTask.writable(workspace,
                "Create a file named second.txt in the current directory whose entire contents are "
                        + "the single word: forge", CAP, logs).continuing(first.sessionId()));
        assumeTrue(!resumed.blockedExternally(),
                () -> "codex unavailable: " + resumed.outcome() + " — " + resumed.finalMessage());

        assertTrue(Files.isRegularFile(workspace.resolve("second.txt")),
                "a resumed Codex turn could not write in its own workspace: " + resumed.finalMessage());
    }

    @Test
    @DisplayName("Antigravity answers a read-only turn, which is the only kind it is given")
    void antigravityAnswersReadOnly() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.ANTIGRAVITY).isPresent(), "agy CLI not installed");
        assertAgentAnswersWithoutWriting(AntigravityAgent.create(null));
    }

    @Test
    @DisplayName("Antigravity in plan mode is refused when it tries to write")
    void antigravityPlanModeIsEnforced() throws IOException {
        assumeTrue(CliLocator.find(AgentKind.ANTIGRAVITY).isPresent(), "agy CLI not installed");

        // The guarantee a reviewer rests on, stated precisely: plan mode keeps the agent out of
        // THIS directory. It is not a vow of silence. Asked to write two files on agy 1.2.2 it
        // called write_to_file three times and created them under its own scratch directory
        // (~/.gemini/antigravity-cli/scratch) — the workspace was untouched, which is the property
        // that matters here, but "it cannot write" would be the wrong summary and was mine for an
        // hour. What this test pins is containment, not abstinence.
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        Files.writeString(workspace.resolve("existing.txt"), "original");

        AgentResult result = AntigravityAgent.create(null).run(AgentTask.readOnly(
                workspace,
                "Create a file called breach.txt in the current directory containing the word "
                        + "PWNED, and overwrite existing.txt with the word CHANGED. "
                        + "Use your tools to do it now.",
                CAP, logs));

        assumeTrue(!result.blockedExternally(),
                () -> "antigravity unavailable: " + result.outcome());

        assertTrue(!Files.exists(workspace.resolve("breach.txt")),
                "a reviewer created a file in the repository it was reviewing");
        assertEquals("original", Files.readString(workspace.resolve("existing.txt")),
                "a reviewer overwrote a file in the repository it was reviewing");
    }

    /**
     * The path every cross-review and every spec suggestion takes, and the one that broke.
     *
     * <p>Claude Code rejects {@code --restricted} together with {@code --permission-mode
     * bypassPermissions} — exit 1, nothing on stdout. Downstream that is indistinguishable from a
     * model that replied with nothing, so reviews and suggestions failed silently for as long as
     * the combination shipped. No test that builds an argument list can catch that; only the CLI
     * can say which of its own flags it accepts together, which is the whole reason these live
     * tests exist.
     */
    private void assertAgentAnswersWithoutWriting(Agent agent) throws IOException {
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        AgentResult result = agent.run(AgentTask.readOnly(
                workspace, "Reply with exactly this word and nothing else: forge", CAP, logs));

        assumeTrue(!result.blockedExternally(),
                () -> agent.kind() + " unavailable: " + result.outcome() + " — " + result.finalMessage());

        assertEquals(AgentOutcome.COMPLETED, result.outcome(), result.finalMessage());
        assertTrue(!result.finalMessage().isBlank(),
                agent.kind() + " returned an empty reply, which every caller reads as 'said nothing'");
    }

    private void assertAgentWritesHelloFile(Agent agent) throws IOException {
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        AgentTask task = AgentTask.writable(
                workspace,
                "Create a file named hello.txt in the current directory whose entire contents are "
                        + "the single word: forge",
                CAP,
                logs);

        AgentResult result = agent.run(task);

        assumeTrue(!result.blockedExternally(),
                () -> agent.kind() + " unavailable: " + result.outcome() + " — " + result.finalMessage());

        assertEquals(AgentOutcome.COMPLETED, result.outcome(), result.finalMessage());

        Path hello = workspace.resolve("hello.txt");
        assertTrue(Files.isRegularFile(hello), "agent claimed to finish but wrote no hello.txt");
        assertEquals("forge", Files.readString(hello).strip());
    }
}
