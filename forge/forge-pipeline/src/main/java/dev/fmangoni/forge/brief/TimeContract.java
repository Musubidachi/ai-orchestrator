package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanStep;
import dev.fmangoni.forge.project.TimeBudget;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * The time constraint and the handoff contract, shared by every kind of agent turn.
 *
 * <p>An agent that does not know its budget spends it the same way whether the task needs ten
 * minutes or ten hours, and gets killed mid-edit having recorded nothing. Stating the budget and
 * demanding a handoff turns a task too large for one session from a repeated failure into a
 * sequence of sessions that build on each other.
 */
@UtilityClass
public class TimeContract {

    /** How an agent can read the clock, which it otherwise has no way to do. */
    private final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The budget, how to spend it, and the handoff Forge will read afterwards. */
    public String of(Duration cap) {
        long working = TimeBudget.working(cap).toMinutes();
        LocalDateTime deadline =
                LocalDateTime.now().plus(TimeBudget.working(cap));
        String checkCommand = WINDOWS
                ? "powershell -NoProfile -Command Get-Date -Format HH:mm:ss"
                : "date +%H:%M:%S";

        return """
                ## Your time for this session

                Work until **%s** local time — about %d minutes from now. Forge terminates this
                process %d minutes after that.

                You cannot feel time passing, so do not estimate it. Run `%s` when you want to know
                how long is left. Check it a few times, not once at the start.

                **Do not stop early.** The budget is a limit, not a target, and stopping at a
                fraction of it is the most expensive mistake available here: every session pays the
                full cost of reading the spec and rebuilding its understanding of the code, so
                finishing at a quarter of your time turns one session's work into four sessions'
                overhead. If you think you are near the end of your time, check the clock before
                acting on it.

                Nothing is lost if you are still working when the process is stopped. Forge commits
                whatever is on disk and the next session continues from it. The reserve exists so
                you can write the handoff, not so you can finish comfortably early.

                Spend the time so that stopping leaves something worth having:

                - Get one narrow slice working end to end before broadening it. A project with one
                real feature and passing tests is worth far more to the next session than eight
                half-built ones.
                - Prefer a small correct piece over a large unfinished one.
                - Leave the tree in a state that builds. The next session starts from your commit,
                and time it spends untangling a broken checkout is time it does not spend building.

                **When a slice lands, take the next one.** Finishing something is not the end of the
                session; it is the moment to check the clock and start the next item on your own
                remaining list. If that list is empty, look at `not_verified` from last time and
                close one of those gaps, or write the test you know is missing. Sessions here
                routinely stop at a third of their time with a working slice and an untouched
                queue, and each of those early stops costs a whole session's worth of reading the
                spec and relearning the code to recover.

                Stop before the deadline only if what remains genuinely cannot be started in the
                time left — and say which item you left and why, in the handoff.

                If the task genuinely cannot be finished in the time given, that is an expected
                answer, not a failure. Say so in the handoff and describe what remains.

                ## Handoff — required, every session

                End your final message with this JSON object and nothing after it:

                {
                  "status": "complete" | "partial",
                  "summary": "what you did and where things stand, a short paragraph",
                  "completed": ["what is finished and verified"],
                  "remaining": [
                    {"step": "imperative description",
                     "detail": "how to do it: approach, files, decisions you already made",
                     "estimate_minutes": 30}
                  ],
                  "sessions_needed": 0,
                  "risks": ["anything the next session should know that is not a step"],
                  "not_verified": ["what the acceptance commands do not actually prove"],
                  "overview": "src/main/java/com/example/thing/Pipeline.java",
                  "features": [
                    {"name": "Report export",
                     "demo": "what a recording of it should show, in one sentence"}
                  ],
                  "ideas": [
                    {"name": "Rival leaderboard",
                     "demo": "what it would do and what a recording of it would show"}
                  ],
                  "digest": "a briefing for a successor who has not read this conversation"
                }

                - `status` is `complete` only when you believe the acceptance commands now pass.
                Forge runs them itself regardless, so claiming it early gains nothing and only
                costs a repair round.
                - `overview` is the path to the one class that reads as the whole program's flow,
                described in AGENTS.md. Every session must leave one and name it here; it is what a
                person reads to decide whether they agree with your approach.
                - `features` is what the program does, at the granularity a person would name if
                asked — not every method, and not the whole thing as one item. List them every
                session, including ones already recorded; the list is how anyone knows the project
                grew a capability. You are proposing, not deciding: each is approved or declined by
                the person the program is for, and an approved one owes a recording of itself that
                the gate then insists on. That recording has to be produced by one of the
                acceptance commands — they are deleted before the gate runs, so a clip made by hand
                and committed will not survive. AGENTS.md says where the file goes and how to
                capture one for this stack.
                - `ideas` is what the project could do next that it does not do yet: two to five
                features you would add if asked, most valuable first, each a few words and a
                sentence. Only once the spec's work is complete — leave it empty while you are
                still building what was asked for. Stay inside the spec's Goal and Non-goals: an
                idea that turns the program into something else is not an idea for this one. Do
                not build any of them. They are approved or declined by the person the program is
                for, and an approved one comes back to you as a request.
                - `not_verified` is the most useful thing you can write. Do not argue that the work
                is correct — the acceptance commands settle that, and a persuasive case for code
                that does not work is worse than no case at all. Write the opposite: where the
                checks are thin, what you could not test, which behaviour a passing run would still
                let through. Each one is read and ruled on by the person the program is for, who has
                not read the code, so say what could go wrong for them — "a file saved while the
                disk is full may be left half written" — rather than which method lacks a test.
                - `remaining` is ordered, and its `detail` is the valuable part: write down the
                decisions you already made and why, so the next session does not rediscover them
                at full price.
                - `digest` is written for a successor who cannot see this conversation, because at
                some point one will not: this conversation is eventually dropped and a fresh
                session is started from your digest alone. Assume that reader has the repository,
                the git history and the spec, and nothing else. Write down the architecture as you
                actually built it, the decisions you made and why, the approaches you tried and
                abandoned and what was wrong with them, the conventions and invariants you are
                holding to, and anything that surprised you. Rewrite it whole each session rather
                than adding to it — it should always read as a current description of the project,
                not a changelog. Approaches already ruled out are the most valuable part: without
                them your successor will spend a session rediscovering the same dead end.
                - `sessions_needed` is how many further %d-minute sessions the rest would take.
                """.formatted(CLOCK.format(deadline), working,
                cap.minus(TimeBudget.working(cap)).toMinutes(), checkCommand, working);
    }

    /** What the previous session left behind, so this one continues rather than restarting. */
    public String resuming(Plan plan) {
        if (plan == null || !plan.usable()) {
            return "";
        }
        StringBuilder out = new StringBuilder();

        // Included on every turn, not only the first after a conversation is dropped. Any brief
        // can be the one that opens a fresh session, and threading a "is this fresh" flag through
        // all five of them would buy back a couple of paragraphs against a transcript measured in
        // megabytes. It also re-grounds a resumed session in its own durable notes, which is worth
        // more than the tokens it costs.
        if (plan.getDigest() != null && !plan.getDigest().isBlank()) {
            out.append("""
                    ## The project so far

                    Your own notes from the previous session. This may be everything you have: the \
                    conversation that produced it is dropped once it grows expensive, and this is \
                    what carries across.

                    """);
            out.append(plan.getDigest()).append("\n\n");
        }

        out.append("""
                ## Where the last session stopped

                """);
        out.append(plan.getSummary()).append("\n\n");

        append(out, "Already done — do not redo it:", plan.getCompleted());

        if (!plan.getRemaining().isEmpty()) {
            out.append("Planned next, in order:\n");
            for (PlanStep step : plan.getRemaining()) {
                out.append("- ").append(step.getStep());
                if (step.getEstimateMinutes() > 0) {
                    out.append(" (~").append(step.getEstimateMinutes()).append("m)");
                }
                out.append('\n');
                if (step.getDetail() != null && !step.getDetail().isBlank()) {
                    out.append("  ").append(step.getDetail()).append('\n');
                }
            }
            out.append('\n');
        }

        append(out, "Known risks and open questions:", plan.getRisks());

        out.append("""
                That plan came from the previous session, which could not see how things turned \
                out. Follow it where it still makes sense and depart from it where it does not — \
                saying so in your own handoff.

                Treat the list above as this session's queue rather than as a single task. Work \
                down it until the clock says stop, not until the first item is done.

                """);
        return out.toString();
    }

    private void append(StringBuilder out, String heading, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        out.append(heading).append('\n');
        items.forEach(item -> out.append("- ").append(item).append('\n'));
        out.append('\n');
    }
}
