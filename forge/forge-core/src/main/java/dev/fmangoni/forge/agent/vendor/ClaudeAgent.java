package dev.fmangoni.forge.agent.vendor;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.JsonlEvents;
import dev.fmangoni.forge.agent.stream.Signals;
import dev.fmangoni.forge.agent.vendor.model.ClaudeArgs;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * Drives the Claude Code CLI headlessly.
 *
 * <p>The command line itself is {@link ClaudeArgs}. Note there is no turn-count flag in this
 * version of the CLI, so {@link AgentTask#timeout()} is the only bound on a runaway session.
 */
@RequiredArgsConstructor
public final class ClaudeAgent implements Agent {

    private final Path exe;

    /** Model alias such as {@code opus} or {@code sonnet}; {@code null} leaves the CLI default. */
    private final String model;

    public static ClaudeAgent create(String model) {
        return new ClaudeAgent(CliLocator.require(AgentKind.CLAUDE), model);
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CLAUDE;
    }

    @Override
    public AgentResult run(AgentTask task) {
        Path log = task.logDir().resolve("claude.log");
        // Assigning the id ourselves, rather than scraping it from the output afterwards, means
        // the next turn can resume even if this one is killed before it reports anything.
        String session = task.resume() != null ? task.resume() : UUID.randomUUID().toString();

        ProcResult proc = CliRunner.run(
                exe, ClaudeArgs.of(task.readOnly(), task.resume(), session, task.model(), model),
                task.cwd(), task.brief(), task.timeout(), log, SubscriptionOnly.API_KEYS);

        return reconcile(Signals.resultOf(proc, finalMessage(proc)), proc).withSession(session);
    }

    /**
     * Honours {@code is_error} over the event's own {@code subtype}.
     *
     * <p>Observed on this machine: a turn that did nothing at all, because the CLI had no session,
     * still reported {@code subtype: "success"} while {@code is_error} was true. Where the two
     * disagree, {@code is_error} is the one that matched reality, so a flagged turn is never
     * passed on as completed.
     *
     * <p>And it is classified as the failure it is. That turn was a logged-out CLI, and calling it
     * an ordinary failure retried it every ten minutes rather than asking someone to log in.
     */
    private AgentResult reconcile(AgentResult result, ProcResult proc) {
        if (result.outcome() != AgentOutcome.COMPLETED || !flaggedError(proc)) {
            return result;
        }
        return Signals.refusedResultOf(proc, result.finalMessage());
    }

    private boolean flaggedError(ProcResult proc) {
        return JsonlEvents.lastOf(proc.stdout(), "result", "is_error")
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    /** The CLI reports its closing summary in a terminal {@code result} event. */
    private String finalMessage(ProcResult proc) {
        return JsonlEvents.lastOf(proc.stdout(), "result", "result")
                .or(() -> JsonlEvents.firstError(proc.stdout()))
                .orElse("");
    }
}
