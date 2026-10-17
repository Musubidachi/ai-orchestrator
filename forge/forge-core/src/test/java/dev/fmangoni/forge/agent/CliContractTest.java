package dev.fmangoni.forge.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.vendor.CliLocator;
import dev.fmangoni.forge.agent.vendor.model.AntigravityArgs;
import dev.fmangoni.forge.agent.vendor.model.ClaudeArgs;
import dev.fmangoni.forge.agent.vendor.model.CodexArgs;
import dev.fmangoni.forge.process.CommandLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asks each installed CLI whether the flags Forge passes it still exist.
 *
 * <p>This is the gap named as #12 in IMPROVEMENTS.md: the only thing that could catch a vendor
 * changing its command line was {@code LiveAgentTest}, which spends real subscription quota and is
 * excluded from the default build. So the fast suite structurally could not catch the class of bug
 * that has cost the most here — {@code --max-turns} not existing, {@code --skip-git-repo-check}
 * being required, {@code bypassPermissions} being rejected under {@code --restricted}. That last
 * one broke every review and every suggestion for days, silently.
 *
 * <p>{@code --help} closes most of that gap for nothing. It reaches no model, spends no quota and
 * takes about a second, and it catches the failure that actually happens: a flag removed or
 * renamed in an npm update. What it cannot catch is a flag that still exists and now means
 * something else — for that there is still no substitute for running the thing.
 *
 * <p>Skips a vendor that is not installed rather than failing. Antigravity is optional by design,
 * and a fresh clone may have none of them.
 */
class CliContractTest {

    /** Long flags only. Short ones are ambiguous in help text and the long form always appears. */
    private static final Pattern FLAG = Pattern.compile("--[a-z][a-z0-9-]+");

    private String help(AgentKind kind, String... subcommands) {
        Path exe = CliLocator.find(kind).orElse(null);
        assumeTrue(exe != null, kind + " is not installed on this machine");

        List<String> command = new ArrayList<>(CommandLine.build(exe, List.of()));
        command.addAll(List.of(subcommands));
        command.add("--help");
        try {
            Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(proc.waitFor(90, TimeUnit.SECONDS), kind + " --help did not finish");
            return out;
        } catch (Exception e) {
            throw new AssertionError("could not ask " + kind + " for its help", e);
        }
    }

    /**
     * Every long flag in an argv Forge would actually send, without its value.
     *
     * <p>{@code --print=stdin} is one flag and one value, not a flag called {@code --print=stdin}.
     * agy requires that attached form — it rejects a separated empty value — so an extractor that
     * did not split here reported a perfectly good flag as missing.
     */
    private List<String> longFlagsOf(List<String> args) {
        return args.stream()
                .filter(arg -> arg.startsWith("--"))
                .map(arg -> arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg)
                .distinct()
                .toList();
    }

    private void assertAccepts(String help, List<String> args, String what) {
        for (String flag : longFlagsOf(args)) {
            assertTrue(mentions(help, flag),
                    what + " passes " + flag + ", which is no longer in its own --help. "
                            + "The CLI changed under us; check what replaced it before editing "
                            + "this test.");
        }
    }

    /** Matched as a whole flag, so {@code --model} is not satisfied by {@code --models}. */
    private boolean mentions(String help, String flag) {
        Matcher m = FLAG.matcher(help);
        while (m.find()) {
            if (m.group().equals(flag)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("Claude still accepts every flag Forge sends it")
    void claudeContract() {
        String help = help(AgentKind.CLAUDE);

        // Both shapes: a fresh build turn and a resumed read-only review, which differ in four
        // flags and are the two that matter.
        assertAccepts(help, ClaudeArgs.of(false, null, "a-session", null, null), "ClaudeArgs");
        assertAccepts(help, ClaudeArgs.of(true, "a-session", null, "opus", null), "ClaudeArgs");

        // -p is the short form of --print and is what selects headless mode. Asserted directly
        // because the long-flag sweep above cannot see it.
        assertTrue(mentions(help, "--print"), "claude no longer documents --print/-p");
    }

    @Test
    @DisplayName("Codex still accepts every flag Forge sends a fresh exec")
    void codexExecContract() {
        String help = help(AgentKind.CODEX, "exec");
        List<String> args = CodexArgs.of(Path.of("."), Path.of("last.txt"), false, null, null, null);

        assertAccepts(help, args, "CodexArgs");
        assertTrue(mentions(help, "--sandbox"), "codex exec lost --sandbox, which bounds writes");
    }

    @Test
    @DisplayName("Codex resume still refuses the flags CodexArgs deliberately omits")
    void codexResumeContract() {
        String help = help(AgentKind.CODEX, "exec", "resume");

        assertAccepts(help,
                CodexArgs.of(Path.of("."), Path.of("last.txt"), false, "thread-1", null, null),
                "CodexArgs resume");

        // The negative half, and the reason CodexArgs has two branches at all: resume rejects -C
        // and -s outright. If they ever appear here the special case can go — but silently keeping
        // it would be harmless, while silently sending them is an immediate hard failure.
        assertTrue(!mentions(help, "--cd") && !mentions(help, "--sandbox"),
                "codex exec resume now takes --cd/--sandbox; CodexArgs can be simplified");
    }

    @Test
    @DisplayName("Antigravity still accepts every flag Forge sends it")
    void antigravityContract() {
        String help = help(AgentKind.ANTIGRAVITY);
        Duration cap = Duration.ofMinutes(20);

        assertAccepts(help, AntigravityArgs.of(true, null, null, null, cap), "AntigravityArgs");
        assertAccepts(help, AntigravityArgs.of(false, "conv-1", "gemini-3.1-pro-high", null, cap),
                "AntigravityArgs");

        // Named individually because losing any of them is silent rather than loud. Without the
        // stream-json input there is no way to get a brief past Windows' argument cap; without
        // plan mode a reviewer can write to the repository it is reviewing.
        assertTrue(mentions(help, "--input-format"), "agy lost --input-format");
        assertTrue(mentions(help, "--print-timeout"), "agy lost --print-timeout");
        assertTrue(help.contains("plan"), "agy's --mode no longer offers plan");
        assertTrue(help.contains("accept-edits"), "agy's --mode no longer offers accept-edits");
        assertTrue(help.contains("stream-json"), "agy no longer speaks stream-json");
    }
}
