package dev.fmangoni.forge.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.process.ProcResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins classification against output genuinely captured from the CLIs on this machine.
 *
 * <p>The fixtures are verbatim, not paraphrased. Paraphrasing would defeat the point: the whole
 * risk being guarded against is another vendor changing its wording, and a test written against
 * invented copy would keep passing when that happens.
 */
class SignalsTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    /** Verbatim from codex-cli 0.153.4, 2026-09-07. */
    private static final String CODEX_QUOTA_EXHAUSTED = """
            {"type":"thread.started","thread_id":"01a07e7e-3ff9-7d93-a8c7-fdf7b5fe3537"}
            {"type":"turn.started"}
            {"type":"error","message":"You've hit your usage limit. Upgrade to Pro \
            (https://chatgpt.com/explore/pro), visit https://chatgpt.com/codex/settings/usage \
            to purchase more credits or try again at 10:00 PM."}
            {"type":"turn.failed","error":{"message":"You've hit your usage limit."}}
            """;

    /** Verbatim from Claude Code 2.1.263 with no CLI session. */
    private static final String CLAUDE_LOGGED_OUT =
            "{\"type\":\"assistant\",\"message\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"Not logged in \\u00b7 Please run /login\"}]}}";

    private static ProcResult failed(String stdout) {
        return new ProcResult(1, stdout, "", false, Duration.ofSeconds(2));
    }

    /** Verbatim from Claude Code 2.1.263 on 2026-09-10, which the first pattern set missed. */
    private static final String CLAUDE_SESSION_LIMIT =
            "{\"is_error\":true,\"subtype\":\"success\",\"api_error_status\":429,"
                    + "\"result\":\"You've hit your session limit \u00b7 resets 9:40pm (America/New_York)\","
                    + "\"type\":\"result\"}";

    /** Verbatim from run 003's antigravity.log, 2026-09-14: stderr, then the result event. */
    private static final String ANTIGRAVITY_QUOTA_REACHED =
            "[stderr] error: Individual quota reached. Please upgrade your subscription to increase "
                    + "your limits. Resets in 141h2m15s.\n"
                    + "{\"event\":\"result\",\"result\":{\"conversation_id\":\"c8808a9f\",\"status\":"
                    + "\"ERROR\",\"response\":\"\",\"error\":\"Individual quota reached. Please upgrade "
                    + "your subscription to increase your limits. Resets in 141h2m15s.\"}}";

    @Test
    @DisplayName("Antigravity's real refusal is a rate limit, not a reviewer that answered badly")
    void detectsAntigravityQuota() {
        // Four refusals like this one were read as FAILED, and three reviews said "no JSON object
        // found in the reviewer's reply" instead of going to another model.
        assertEquals(AgentOutcome.RATE_LIMITED, Signals.classify(failed(ANTIGRAVITY_QUOTA_REACHED)));
    }

    @Test
    @DisplayName("\"Resets in 141h2m15s\" is a countdown from now")
    void parsesAntigravityCountdown() {
        Instant now = Instant.parse("2026-09-14T09:00:00Z");

        assertEquals(now.plus(Duration.ofHours(141).plusMinutes(2).plusSeconds(15)),
                Signals.resetTime(failed(ANTIGRAVITY_QUOTA_REACHED), ZONE, now).orElseThrow());
        assertEquals(now.plus(Duration.ofMinutes(40)),
                Signals.resetTime(failed("Resets in 40m."), ZONE, now).orElseThrow());
        assertEquals(Optional.empty(),
                Signals.resetTime(failed("resets in a while"), ZONE, now));
    }

    @Test
    @DisplayName("Claude's own wording is recognised, not just Codex's")
    void detectsClaudeSessionLimit() {
        assertEquals(AgentOutcome.RATE_LIMITED, Signals.classify(failed(CLAUDE_SESSION_LIMIT)));
    }

    @Test
    @DisplayName("\"resets 9:40pm\" parses as well as \"try again at 10:00 PM\"")
    void parsesClaudeResetWording() {
        Instant now = LocalDateTime.of(2026, 9, 10, 20, 0)
                .atZone(ZONE).toInstant();

        assertEquals(LocalDateTime.of(2026, 9, 10, 21, 40).atZone(ZONE).toInstant(),
                Signals.resetTime(failed(CLAUDE_SESSION_LIMIT), ZONE, now).orElseThrow());
    }

    /** Verbatim from the quota ledger on 2026-10-03: a window that turns over on the hour. */
    private static final String CLAUDE_SESSION_LIMIT_ON_THE_HOUR =
            "{\"is_error\":true,\"api_error_status\":429,"
                    + "\"result\":\"You've hit your session limit " + (char) 0xB7 + " resets 4pm (America/New_York)\","
                    + "\"type\":\"result\"}";

    @Test
    @DisplayName("\"resets 4pm\", with no minutes, is read: it was not, and Forge guessed fifteen minutes")
    void parsesAResetOnTheHour() {
        Instant now = LocalDateTime.of(2026, 10, 3, 11, 30).atZone(ZONE).toInstant();

        assertEquals(LocalDateTime.of(2026, 10, 3, 16, 0).atZone(ZONE).toInstant(),
                Signals.resetTime(failed(CLAUDE_SESSION_LIMIT_ON_THE_HOUR), ZONE, now).orElseThrow());
        assertEquals(LocalDateTime.of(2026, 10, 3, 22, 0).atZone(ZONE).toInstant(),
                Signals.resetTime(failed("or try again at 10 PM."), ZONE, now).orElseThrow(),
                "Codex's wording with no minutes, read the same way");
    }

    @Test
    @DisplayName("the zone Claude names is the zone the reset is read in, whatever this machine is set to")
    void readsTheResetInTheZoneTheVendorNames() {
        ZoneId machine = ZoneId.of("Europe/London");
        Instant now = LocalDateTime.of(2026, 10, 3, 11, 30).atZone(ZONE).toInstant();

        assertEquals(LocalDateTime.of(2026, 10, 3, 16, 0).atZone(ZONE).toInstant(),
                Signals.resetTime(failed(CLAUDE_SESSION_LIMIT_ON_THE_HOUR), machine, now).orElseThrow(),
                "4pm in New York, not 4pm in London");
        // 3pm in London: before four there, so the reset is today.
        Instant beforeFourInLondon = LocalDateTime.of(2026, 10, 3, 15, 0).atZone(machine).toInstant();
        assertEquals(LocalDateTime.of(2026, 10, 3, 16, 0).atZone(machine).toInstant(),
                Signals.resetTime(failed("resets 4pm (Mars/Olympus_Mons)"), machine, beforeFourInLondon).orElseThrow(),
                "a zone that does not exist costs the zone, not the reset time");
    }

    @Test
    @DisplayName("a classified refusal on the hour carries its reset time into the result")
    void theResultCarriesAResetOnTheHour() {
        AgentResult result = Signals.resultOf(failed(CLAUDE_SESSION_LIMIT_ON_THE_HOUR), "");

        assertEquals(AgentOutcome.RATE_LIMITED, result.outcome());
        assertNotNull(result.retryAfter(), "an empty reset time is what made the ledger guess fifteen minutes");
    }

    @Nested
    @DisplayName("classify")
    class Classify {

        @Test
        void detectsCodexQuotaExhaustion() {
            assertEquals(AgentOutcome.RATE_LIMITED, Signals.classify(failed(CODEX_QUOTA_EXHAUSTED)));
        }

        @Test
        void detectsMissingClaudeSession() {
            assertEquals(AgentOutcome.NOT_AUTHENTICATED, Signals.classify(failed(CLAUDE_LOGGED_OUT)));
        }

        @Test
        @DisplayName("an ordinary non-zero exit is a real failure, not a refusal to wait out")
        void ordinaryFailureIsNotExcused() {
            assertEquals(AgentOutcome.FAILED, Signals.classify(failed("BUILD FAILURE\nTests run: 3, Failures: 1")));
        }

        @Test
        void successIsCompleted() {
            ProcResult ok = new ProcResult(0, "done", "", false, Duration.ofSeconds(1));
            assertEquals(AgentOutcome.COMPLETED, Signals.classify(ok));
        }

        @Test
        @DisplayName("a timeout outranks output, since a killed process may say anything")
        void timeoutWins() {
            ProcResult killed = new ProcResult(-1, CODEX_QUOTA_EXHAUSTED, "", true, Duration.ofHours(1));
            assertEquals(AgentOutcome.TIMED_OUT, Signals.classify(killed));
        }

        /** Verbatim from run 004, which spent seven turns on it. */
        private String sandboxRejection() {
            return "[stderr] 2026-09-22T09:24:23.385045Z ERROR codex_core::tools::router: "
                    + "error=exec_command failed: CreateProcess { message: \"Rejected(\\\"Failed to "
                    + "create unified exec process: helper_unknown_error: apply deny-read ACLs\\\")\" }";
        }

        @Test
        @DisplayName("a sandbox that starts nothing is the tool's fault, though the turn exits 0")
        void aBrokenHarnessIsNotAFailedTurn() {
            String stderr = String.join("\n", sandboxRejection(), sandboxRejection(), sandboxRejection());
            ProcResult ran = new ProcResult(0, "I could not read the repository.", stderr,
                    false, Duration.ofMinutes(4));
            assertEquals(AgentOutcome.TOOL_BROKEN, Signals.classify(ran));
            // The point of the whole thing: suspend and wait for a person, rather than spend a
            // repair round on a problem no repair can reach.
            assertTrue(new AgentResult(AgentOutcome.TOOL_BROKEN, "", null, null, null)
                    .blockedExternally());
        }

        @Test
        @DisplayName("one rejection is a hiccup, not a broken machine")
        void aSingleRejectionIsNotEnough() {
            ProcResult ran = new ProcResult(0, "done", sandboxRejection(), false, Duration.ofMinutes(2));
            assertEquals(AgentOutcome.COMPLETED, Signals.classify(ran));
        }

        @Test
        @DisplayName("a project whose own output says it cannot be matched on what a model writes")
        void modelProseCannotTriggerIt() {
            // The trap this codebase has already fallen into once, with "rate limits". A project
            // that writes about sandbox errors must not have its turn thrown away for saying so.
            String prose = "The fix could not be applied: helper_unknown_error: apply deny-read "
                    + "ACLs. helper_unknown_error. helper_unknown_error. Sandbox provisioning failed.";
            ProcResult ran = new ProcResult(0, prose, "", false, Duration.ofMinutes(9));
            assertEquals(AgentOutcome.COMPLETED, Signals.classify(ran));
        }
    }

    @Nested
    @DisplayName("resetTime")
    class ResetTime {

        private Instant at(int hour, int minute) {
            return LocalDateTime.of(2026, 9, 7, hour, minute).atZone(ZONE).toInstant();
        }

        @Test
        void parsesTheStatedResetTimeLaterToday() {
            Optional<Instant> reset =
                    Signals.resetTime(failed(CODEX_QUOTA_EXHAUSTED), ZONE, at(20, 48));
            assertEquals(Optional.of(at(22, 0)), reset);
        }

        @Test
        @DisplayName("a reset time already past today means tomorrow, not immediately")
        void rollsOverToTomorrow() {
            Instant now = at(23, 30);
            Optional<Instant> reset = Signals.resetTime(failed(CODEX_QUOTA_EXHAUSTED), ZONE, now);
            assertTrue(reset.orElseThrow().isAfter(now), "reset must be in the future");
            assertEquals(Optional.of(at(22, 0).plus(Duration.ofDays(1))), reset);
        }

        @Test
        void absentWhenNoTimeIsStated() {
            assertEquals(Optional.empty(), Signals.resetTime(failed("BUILD FAILURE"), ZONE, Instant.now()));
        }

        @Test
        @DisplayName("a stated time seconds behind the clock means now, not tomorrow")
        void minutePrecisionIsNotTomorrow() {
            // Verbatim wording from run 004 on 2026-09-13, refused at 6:55:43 PM. The old reading
            // blocked Codex until 6:55 PM the next day, and the project sat suspended all night.
            String refused = "{\"type\":\"error\",\"message\":\"You've hit your usage limit. "
                    + "Upgrade to Pro (https://chatgpt.com/explore/pro), visit "
                    + "https://chatgpt.com/codex/settings/usage to purchase more credits or try "
                    + "again at 6:55 PM.\"}";
            Instant now = at(18, 55).plusSeconds(43);

            Instant reset = Signals.resetTime(failed(refused), ZONE, now).orElseThrow();

            assertTrue(reset.isBefore(now.plus(Duration.ofMinutes(5))),
                    "a window that reopened within the minute was pushed to " + reset);
            assertTrue(reset.isAfter(now), "a retry must still wait a moment");
        }

        @Test
        @DisplayName("Codex's weekly limit names a date, and that date is honoured")
        void datedResetIsParsed() {
            // Previously unmatched, so these fell back to the default back-off instead.
            String weekly = "{\"type\":\"error\",\"message\":\"You've hit your usage limit. "
                    + "try again at Sep 13th, 2026 12:40 AM.\"}";

            assertEquals(
                    Optional.of(LocalDateTime.of(2026, 9, 13, 0, 40).atZone(ZONE).toInstant()),
                    Signals.resetTime(failed(weekly), ZONE, at(20, 0)));
        }
    }

    @Nested
    @DisplayName("what a successful turn happens to say")
    class Prose {

        private ProcResult succeeded(String stdout) {
            return new ProcResult(0, stdout, "", false, Duration.ofMinutes(6));
        }

        @Test
        @DisplayName("a finished turn that mentions rate limits is not a refusal")
        void rateLimitProseInASuccessfulTurn() {
            // Measured: project 006 generates business ideas, and its own template data reads "a
            // proxy API that pools and manages rate limits". Two turns exited 0 after five and
            // eight minutes of real work, matched "rate limit", and were classified as refused —
            // suspending the project, blocking the vendor, and running the same turn again.
            String transcript = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":"
                    + "\"text\",\"text\":\"{Niche} Rate-Limit Proxy: A proxy API that pools and "
                    + "manages rate limits for {niche} services\"}]}}\n"
                    + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false}";

            assertEquals(AgentOutcome.COMPLETED, Signals.classify(succeeded(transcript)));
        }

        @Test
        @DisplayName("a finished turn that builds a login page is not logged out")
        void authProseInASuccessfulTurn() {
            String transcript = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":"
                    + "\"text\",\"text\":\"Added LoginPage.tsx: shows 'Please sign in' when "
                    + "the session has expired\"}]}}";

            assertEquals(AgentOutcome.COMPLETED, Signals.classify(succeeded(transcript)));
        }

        @Test
        @DisplayName("the same words from a failed call are still a refusal")
        void failedCallsAreStillClassified() {
            assertEquals(AgentOutcome.RATE_LIMITED,
                    Signals.classify(failed(CODEX_QUOTA_EXHAUSTED)));
        }
    }

    @Nested
    @DisplayName("codex-cli 0.159.3")
    class Codex0159 {

        /** Verbatim: asked for a model the account cannot use. Exit 1, nothing on stderr. */
        private static final String UNSUPPORTED_MODEL =
                "{\"type\":\"thread.started\",\"thread_id\":\"01a0f7fd-4835-7ff2-a966-33bd0bcb0b34\"}\n"
                + "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"error\",\"message\":"
                + "\"Model metadata for `no-such-model-xyz` not found. Defaulting to fallback metadata; "
                + "this can degrade performance and cause issues.\"}}\n"
                + "{\"type\":\"turn.started\"}\n"
                + "{\"type\":\"error\",\"message\":\"{\\\"type\\\":\\\"error\\\",\\\"status\\\":400,"
                + "\\\"error\\\":{\\\"type\\\":\\\"invalid_request_error\\\",\\\"message\\\":\\\"The "
                + "'no-such-model-xyz' model is not supported when using Codex with a ChatGPT "
                + "account.\\\"}}\"}\n"
                + "{\"type\":\"turn.failed\",\"error\":{\"message\":\"{\\\"type\\\":\\\"error\\\","
                + "\\\"status\\\":400,\\\"error\\\":{\\\"type\\\":\\\"invalid_request_error\\\","
                + "\\\"message\\\":\\\"The 'no-such-model-xyz' model is not supported when using "
                + "Codex with a ChatGPT account.\\\"}}\"}}";

        /** Verbatim: one of the router's records for a command the sandbox refused to start. */
        private static final String REFUSED = "2026-10-01T14:58:44.970046Z ERROR "
                + "codex_core::tools::router: error=exec_command failed: CreateProcess { message: "
                + "\"Rejected(\\\"Failed to create unified exec process: helper_unknown_error: "
                + "apply deny-read ACLs\\\")\" }";

        /** The router's record for a patch that did not apply: its first line, then the patch's own text. */
        private String failedPatch(String patchText) {
            return "2026-09-28T01:15:07.936673Z ERROR codex_core::tools::router: error=apply_patch "
                    + "verification failed: Failed to find expected lines in C:\\runs\\004\\repo\\README.md:\n"
                    + patchText;
        }

        @Test
        @DisplayName("an unsupported model is an ordinary failure, whatever its envelope looks like")
        void unsupportedModelIsAFailure() {
            assertEquals(AgentOutcome.FAILED, Signals.classify(failed(UNSUPPORTED_MODEL)));
        }

        @Test
        @DisplayName("a sandbox that starts nothing is still recognised, in this version's own lines")
        void brokenSandboxIsStillRecognised() {
            // What the writable probe logged: file writes failing between refused commands.
            String stderr = String.join("\n",
                    "2026-10-01T14:58:35.560587Z ERROR codex_core::tools::router: error=Exit code: 1",
                    "Wall time: 1.7 seconds", "Output:", "Failed to write file C:\\probe\\hello.txt", "",
                    REFUSED, REFUSED, REFUSED);
            ProcResult ran = new ProcResult(0, "I couldn't create hello.txt.", stderr, false,
                    Duration.ofMinutes(3));

            assertEquals(AgentOutcome.TOOL_BROKEN, Signals.classify(ran));
        }

        @Test
        @DisplayName("a patch that quotes the sandbox error is not a broken sandbox")
        void patchTextIsNotTheClisVoice() {
            // Codex prints a failed patch's own lines on stderr, under the record. The project
            // that found this error went on to write about it, in files it then patched.
            String quoting = "helper_unknown_error: apply deny-read ACLs\n".repeat(4);
            ProcResult ran = new ProcResult(0, "done", failedPatch(quoting), false, Duration.ofMinutes(9));

            assertEquals(AgentOutcome.COMPLETED, Signals.classify(ran));
        }

        @Test
        @DisplayName("a failed turn whose patch mentions rate limits or signing in is a failure")
        void patchTextCannotLookLikeARefusal() {
            String patch = "- app.use(rateLimit()) // you have hit your usage limit\n"
                    + "+ <p>Please sign in. Not logged in.</p>\n";
            ProcResult ran = new ProcResult(1, "", failedPatch(patch), false, Duration.ofMinutes(9));

            assertEquals(AgentOutcome.FAILED, Signals.classify(ran));
        }

        @Test
        @DisplayName("what follows a tool record is dropped only until the next record")
        void otherLoggersAreKeptWhole() {
            String stderr = failedPatch("the patch's own text\n")
                    + "2026-10-01T14:57:10.562764Z ERROR codex_memories_write::phase2: Phase 2 no changes\n"
                    + "a line the CLI printed for itself";

            String own = CliWords.ownStderr(stderr);

            assertTrue(own.contains("apply_patch verification failed"), "the record's first line is the CLI's");
            assertTrue(!own.contains("the patch's own text"), own);
            assertTrue(own.contains("a line the CLI printed for itself"), own);
        }

        @Test
        @DisplayName("stderr from a CLI that does not timestamp its lines is kept as it is")
        void plainStderrIsUntouched() {
            String agy = "error: Individual quota reached. Please upgrade your subscription to "
                    + "increase your limits. Resets in 141h2m15s.\n";

            assertEquals(agy, CliWords.ownStderr(agy));
        }

        @Test
        @DisplayName("PROVISIONAL: a 429 in the same envelope would be read as a usage limit")
        void aWrapped429IsAQuotaRefusal() {
            // Not observed. Every usage limit in Forge's logs is plain text, the last from before
            // this version; if 0.159.3 wraps it as it wraps a 400, the status is what is matched.
            String wrapped = "{\"type\":\"error\",\"message\":\"{\\\"type\\\":\\\"error\\\","
                    + "\\\"status\\\":429,\\\"error\\\":{\\\"type\\\":\\\"usage_limit_reached\\\","
                    + "\\\"message\\\":\\\"The usage limit has been reached\\\"}}\"}";

            assertEquals(AgentOutcome.RATE_LIMITED, Signals.classify(failed(wrapped)));
        }
    }

    @Nested
    @DisplayName("what a failed turn happens to say")
    class FailedProse {

        /** A real turn: the model used tools, then the CLI died for a reason of its own. */
        private ProcResult crashedAfterWorking(String modelSaid, String toolSaw) {
            String stdout = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                    + "\"name\":\"Read\",\"input\":{\"file_path\":\"src/server.js\"}}]}}\n"
                    + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                    + "\"content\":\"" + toolSaw + "\"}]}}\n"
                    + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"" + modelSaid + "\"}]}}\n"
                    + "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                    + "\"result\":\"\"}";
            return new ProcResult(1, stdout, "", false, Duration.ofMinutes(12));
        }

        @Test
        @DisplayName("a failed turn on a project with a rate limiter is a failure, not a quota refusal")
        void rateLimitProseInAFailedTurn() {
            // Before: any failed turn whose transcript said "rate limit" anywhere — the model's
            // words, or a file it read — suspended the project and blocked the vendor for all.
            ProcResult proc = crashedAfterWorking(
                    "Added express-rate-limit: the rate limit is 100 requests a minute",
                    "const limiter = rateLimit({ windowMs: 60000 }); // rate limited per IP");

            assertEquals(AgentOutcome.FAILED, Signals.classify(proc));
        }

        @Test
        @DisplayName("a failed turn on an app with a sign-in page is a failure, not a logged-out CLI")
        void authProseInAFailedTurn() {
            ProcResult proc = crashedAfterWorking("The header now shows Please sign in",
                    "<p>Please sign in to continue</p>");

            assertEquals(AgentOutcome.FAILED, Signals.classify(proc));
        }

        @Test
        @DisplayName("a project's own clock times are not a vendor naming its reset")
        void resetTimeIsNotReadFromProse() {
            ProcResult proc = crashedAfterWorking("The banner says try again at 9:00 PM",
                    "resets in 3h");

            assertEquals(Optional.empty(), Signals.resetTime(proc, ZONE, Instant.now()));
        }

        @Test
        @DisplayName("the CLI's own refusal still counts after the model has worked")
        void aRefusalAfterWorkIsStillARefusal() {
            // The limit can land mid-turn. The result event is the CLI's, whatever came before it.
            String stdout = crashedAfterWorking("half done", "x").stdout() + "\n" + CLAUDE_SESSION_LIMIT;

            assertEquals(AgentOutcome.RATE_LIMITED, Signals.classify(failed(stdout)));
        }

        @Test
        @DisplayName("Claude's is_error on a clean exit is classified, so a logged-out CLI says so")
        void flaggedCleanExitIsClassified() {
            ProcResult loggedOut = new ProcResult(0, CLAUDE_LOGGED_OUT + "\n"
                    + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true}",
                    "", false, Duration.ofSeconds(2));

            assertEquals(AgentOutcome.NOT_AUTHENTICATED, Signals.refusal(loggedOut));
        }
    }
}
