package dev.fmangoni.forge.agent.stream;

import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.process.ProcResult;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Classifies raw CLI output into an {@link AgentOutcome}.
 *
 * <p>Neither CLI uses a distinct exit code for quota exhaustion versus an ordinary failure — both
 * exit 1 — so classification is necessarily string matching, and string matching against another
 * vendor's user-facing copy is inherently brittle. Three things keep that honest:
 *
 * <ul>
 *   <li>Every pattern below is marked with whether it was <b>verified</b> against real captured
 *       output on this machine or is <b>provisional</b> (written from the CLI's documented
 *       behaviour, not yet observed). {@code SignalsTest} pins the verified ones against fixtures.
 *   <li>The patterns are matched only against what the CLI said in its own voice ({@link CliWords}),
 *       never against the model's prose or the files it read — and a clean exit is not matched at
 *       all.
 *   <li>Misclassification is designed to be cheap in one direction: an unrecognised rate-limit
 *       message degrades to {@link AgentOutcome#FAILED}, which never reports a broken project as
 *       done. A failed call that changed nothing is retried without charging any budget, and
 *       handed to a person if it keeps happening.
 * </ul>
 */
@UtilityClass
public class Signals {

    /**
     * The CLI's own logger saying it could not create a process.
     *
     * <p>Written by the tool, never by a model: {@code codex_core::tools::router} rejecting process
     * creation, and the Windows sandbox provisioning error underneath it.
     */
    private final Pattern HARNESS_REFUSED = Pattern.compile(
            "(?i)helper_unknown_error|failed to create unified exec process|sandbox provisioning failed");

    /** One rejection is a hiccup; a turn that cannot start anything repeats it without end. */
    private final int BROKEN_ENOUGH = 3;

    /** VERIFIED — captured from codex-cli 0.153.4 on 2026-09-07. */
    private final Pattern CODEX_RATE_LIMIT =
            Pattern.compile("hit your usage limit", Pattern.CASE_INSENSITIVE);

    /**
     * VERIFIED — the clock shapes seen in the wild: Codex's "...or try again at 10:00 PM.", and
     * Claude's "resets 9:40pm (America/New_York)" — or "resets 4pm (America/New_York)" when the
     * window turns over on the hour, which Claude writes with no minutes.
     *
     * <p>The minutes were required, and on 2026-10-03 "resets 4pm" was not read at all: the ledger
     * recorded no reset time, the dashboard said Claude was back at 11:45 AM, and the queue would
     * have retried every fifteen minutes until four. The zone Claude names is captured as well, so
     * the reset is read in the vendor's zone rather than whatever this machine is set to.
     */
    private final Pattern RESET_AT = Pattern.compile(
            "(?:try again at|resets(?: at)?)\\s*(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))"
                    + "(?:\\s*\\(([A-Za-z]+(?:/[A-Za-z0-9_+-]+)+)\\))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * VERIFIED — Codex's weekly limit names a date: "try again at Sep 13th, 2026 12:40 AM".
     *
     * <p>Missed by {@link #RESET_AT}, which wants the clock straight after "try again at", so these
     * suspensions fell back to the default back-off instead of the day the vendor actually named.
     */
    private final Pattern RESET_ON = Pattern.compile(
            "try again at\\s+([A-Z][a-z]{2,8})\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\s+"
                    + "(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))",
            Pattern.CASE_INSENSITIVE);

    /**
     * VERIFIED — Antigravity states a countdown rather than a clock: "Resets in 141h2m15s."
     *
     * <p>At least one unit is required, so "resets in" followed by anything else matches nothing.
     */
    private final Pattern RESETS_IN = Pattern.compile(
            "resets in\\s+(?=\\d+[hms])(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * How far in the past a stated clock time can be and still mean "now".
     *
     * <p>Codex reports minutes. Refused at 6:55:43 PM and told "try again at 6:55 PM", the old code
     * read a time already past as tomorrow and blocked Codex for twenty-four hours; a project sat
     * suspended all night behind a window that had reopened within the minute. Neither vendor's
     * rolling window is anywhere near a day long, so a bare time a few minutes behind is rounding.
     */
    private final Duration JUST_PASSED = Duration.ofMinutes(15);

    /**
     * VERIFIED — captured from Claude Code 2.1.263 on 2026-09-10:
     * {@code "You've hit your session limit · resets 9:40pm (America/New_York)"} with
     * {@code "api_error_status":429}.
     *
     * <p>The 429 is matched first and matters most: it is a number the vendor emits rather than
     * prose it may reword. The earlier version of this pattern looked only for "usage limit
     * reached" and missed "session limit" entirely, so a rate-limited turn was classified as an
     * ordinary failure and spent a repair round — six of them, in sixteen seconds.
     */
    private final Pattern CLAUDE_RATE_LIMIT = Pattern.compile(
            "\"api_error_status\"\s*:\s*429"
                    + "|hit your session limit|session limit"
                    + "|usage limit reached|rate limit(?:ed)?|limit will reset"
                    + "|approaching your usage limit",
            Pattern.CASE_INSENSITIVE);

    /**
     * VERIFIED for "quota reached" — agy on 2026-09-14, four times across three projects:
     * "Individual quota reached. Please upgrade your subscription to increase your limits. Resets
     * in 141h2m15s." Exit 1, on stderr and in the {@code result} event's {@code error}. The rest is
     * provisional, written from Google's wire values.
     *
     * <p>The guess before it matched none of that. Every refusal was read as an ordinary failure,
     * so the ledger never learned Antigravity was out, reviews kept going to it first, and three
     * projects' reviews said "no JSON object found in the reviewer's reply" — which reads as a
     * reviewer that answered badly, rather than one that never answered.
     */
    private final Pattern ANTIGRAVITY_RATE_LIMIT = Pattern.compile(
            "quota reached|RESOURCE_EXHAUSTED|rateLimitExceeded"
                    + "|quota exceeded|exceeded your current quota"
                    + "|\\bstatus\\W{0,3}429\\b|\"code\"\\s*:\\s*429",
            Pattern.CASE_INSENSITIVE);

    /**
     * VERIFIED for Claude Code 2.1.263 ("Not logged in · Please run /login") and agy 1.2.2
     * ("Please sign in to view available models. Launch the CLI without arguments to sign in.");
     * the rest is provisional.
     */
    private final Pattern NOT_AUTHENTICATED = Pattern.compile(
            "not logged in|please run /login|please run `?codex login|"
                    + "please sign in|launch the cli without arguments|"
                    + "please set an auth method|"
                    + "no credentials found|authentication (?:failed|required)",
            Pattern.CASE_INSENSITIVE);

    private final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /**
     * Classifies a finished invocation.
     *
     * <p>Order matters: authentication is checked before quota because a logged-out CLI can emit
     * copy mentioning limits, and the remedy for the two is completely different.
     */
    public AgentOutcome classify(ProcResult proc) {
        if (proc.timedOut()) {
            return AgentOutcome.TIMED_OUT;
        }
        // A process that exited cleanly finished its turn, whatever its output says. The patterns
        // below are phrases, and they were being matched against everything the CLI printed —
        // which for a stream-json agent is the model's prose and every file it read. One project
        // here generates business ideas, and its own template data says "a proxy API that pools
        // and manages rate limits": two of its turns exited 0 after five and eight minutes of real
        // work, and each matched "rate limit" and was classified as a refusal. A refusal suspends
        // the project, blocks the vendor for every other project, and runs the same turn again.
        //
        // Measured before changing it: across 91 recorded invocations of all three CLIs, every
        // genuine rate limit exited non-zero, and no exit-0 turn carried a real 429. A login page
        // saying "please sign in" was the same trap waiting for the auth patterns.
        //
        // Assumed, not observed: that a logged-out CLI exits non-zero too. If one ever exits 0, this
        // costs a repair round when the gate finds nothing was built — the cheap direction.
        // Before the exit-code check, because this turn exits 0: the CLI starts, the model writes a
        // summary explaining it could do nothing, and only its own logger knows why.
        if (toolBroken(proc)) {
            return AgentOutcome.TOOL_BROKEN;
        }
        if (proc.exitCode() == 0) {
            return AgentOutcome.COMPLETED;
        }
        return refusal(proc);
    }

    /**
     * Why a call that did not succeed failed: a refusal the CLI named, or an ordinary failure.
     *
     * <p>Public for the one caller that knows a call failed despite a clean exit — Claude flags
     * {@code is_error} on a turn that exited 0, which is how a logged-out CLI reports itself.
     *
     * <p>Matched against {@link CliWords} only. The same phrases matched against everything the
     * CLI printed classified any failed turn on a web app with a rate limiter as a quota refusal,
     * and any on an app with a sign-in page as a logged-out CLI.
     */
    public AgentOutcome refusal(ProcResult proc) {
        String text = CliWords.of(proc);
        if (NOT_AUTHENTICATED.matcher(text).find()) {
            return AgentOutcome.NOT_AUTHENTICATED;
        }
        if (CODEX_RATE_LIMIT.matcher(text).find() || CLAUDE_RATE_LIMIT.matcher(text).find()
                || ANTIGRAVITY_RATE_LIMIT.matcher(text).find()) {
            return AgentOutcome.RATE_LIMITED;
        }
        return AgentOutcome.FAILED;
    }

    /**
     * A CLI whose own sandbox will not start a process.
     *
     * <p>Matched against {@code stderr} alone, and this is the whole reason it is safe. The lesson
     * paid for once already is that phrases must never be matched against what a model writes: a
     * project generating business ideas produced the words "rate limits" in its own output and had
     * two real turns thrown away as refusals. The model's prose goes to stdout; this is the CLI's
     * internal logger, which no project's content can reach.
     *
     * <p>A handful rather than one, because a single rejection is a hiccup and a turn that cannot
     * start anything says so hundreds of times. The run that prompted this logged 258.
     *
     * <p>Still the wording on codex-cli 0.159.3 (2026-10-01): a turn asked to write one file logged
     * five of these before giving up. {@code codex doctor} reports the same failure as
     * {@code error code helper_unknown_error}.
     */
    private boolean toolBroken(ProcResult proc) {
        // The CLI's own lines only. Under a tool record Codex prints the tool's output and the
        // text of a patch that failed to apply, and a project that writes about this very error —
        // as the one that found it did — must not be read as a broken tool for quoting it.
        Matcher rejections = HARNESS_REFUSED.matcher(CliWords.ownStderr(proc.stderr()));
        int seen = 0;
        while (rejections.find() && seen < BROKEN_ENOUGH) {
            seen++;
        }
        return seen >= BROKEN_ENOUGH;
    }

    /**
     * Extracts the quota reset time when the CLI states one, so a suspended run can resume just
     * after the window turns over instead of polling blindly.
     *
     * <p>The CLI reports a wall-clock time with no date, so this resolves to the next occurrence in
     * the local zone.
     */
    public Optional<Instant> resetTime(ProcResult proc, ZoneId zone, Instant now) {
        // The CLI's words only, for the same reason as the refusal itself: a project whose own
        // copy says "try again at 9:00 PM" is not a vendor naming its reset.
        String text = CliWords.of(proc);
        try {
            Matcher countdown = RESETS_IN.matcher(text);
            if (countdown.find()) {
                return Optional.of(now
                        .plus(Duration.ofHours(parsedOrZero(countdown.group(1))))
                        .plus(Duration.ofMinutes(parsedOrZero(countdown.group(2))))
                        .plus(Duration.ofSeconds(parsedOrZero(countdown.group(3)))));
            }

            Matcher dated = RESET_ON.matcher(text);
            if (dated.find()) {
                LocalDate day = LocalDate.of(Integer.parseInt(dated.group(3)),
                        Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
                                .parse(dated.group(1).substring(0, 3))),
                        Integer.parseInt(dated.group(2)));
                return Optional.of(clock(dated.group(4)).atDate(day).atZone(zone).toInstant());
            }

            Matcher m = RESET_AT.matcher(text);
            if (!m.find()) {
                return Optional.empty();
            }
            ZoneId where = named(m.group(2), zone);
            LocalDate today = now.atZone(where).toLocalDate();
            Instant candidate = clock(m.group(1)).atDate(today).atZone(where).toInstant();
            if (candidate.isAfter(now)) {
                return Optional.of(candidate);
            }
            if (!candidate.isBefore(now.minus(JUST_PASSED))) {
                // Minutes of rounding, not tomorrow. A minute's grace so a retry does not land a
                // second before the vendor has actually turned the window over.
                return Optional.of(now.plusSeconds(60));
            }
            return Optional.of(candidate.plusSeconds(86_400));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private long parsedOrZero(String digits) {
        return digits == null ? 0 : Long.parseLong(digits);
    }

    /** Codex writes "10:00 PM", Claude "9:40pm" or, on the hour, "4pm"; all normalise to "H:MM AM". */
    private LocalTime clock(String stamp) {
        return LocalTime.parse(stamp.toUpperCase(Locale.ENGLISH)
                .replaceAll("\\s+", "")
                .replaceAll("^(\\d{1,2})(AM|PM)$", "$1:00$2")
                .replaceAll("(AM|PM)$", " $1"), CLOCK);
    }

    /**
     * The zone the vendor named, or {@code fallback} when it named none or one that is not a zone.
     *
     * <p>A name that is not a zone costs only the zone, never the reset time it came with.
     */
    private ZoneId named(String zone, ZoneId fallback) {
        if (zone == null || zone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    /**
     * Classifies a finished invocation and, when it was rate limited, records when the quota window
     * is expected to reopen.
     *
     * <p>Lives here rather than on {@link AgentResult} so the record stays a record: it was calling
     * back into this class to decide what it was, which is the one dependency that pointed the wrong
     * way inside the agent package. Here rather than in each vendor so the two cannot drift apart in
     * how they decide what a failure means.
     */
    public AgentResult resultOf(ProcResult proc, String finalMessage) {
        return withReset(classify(proc), proc, finalMessage);
    }

    /** As {@link #resultOf}, for a call already known to have failed whatever its exit code said. */
    public AgentResult refusedResultOf(ProcResult proc, String finalMessage) {
        return withReset(refusal(proc), proc, finalMessage);
    }

    private AgentResult withReset(AgentOutcome outcome, ProcResult proc, String finalMessage) {
        Instant retryAfter = outcome == AgentOutcome.RATE_LIMITED
                ? resetTime(proc, ZoneId.systemDefault(), Instant.now()).orElse(null)
                : null;
        return new AgentResult(outcome, finalMessage, proc, retryAfter, null);
    }
}
