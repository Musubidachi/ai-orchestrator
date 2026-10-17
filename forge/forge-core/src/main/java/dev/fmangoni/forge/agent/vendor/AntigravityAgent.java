package dev.fmangoni.forge.agent.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.JsonlEvents;
import dev.fmangoni.forge.agent.stream.Signals;
import dev.fmangoni.forge.agent.vendor.model.AntigravityArgs;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * Drives the Antigravity CLI headlessly.
 *
 * <p>The command line is {@link AntigravityArgs}, which records the flags and why. This class is
 * the invocation and the reading of what came back.
 *
 * <p>Antigravity answers as a stream of NDJSON events, the same shape of problem Codex poses, so
 * {@link JsonlEvents} does the reading. The stream ends with one {@code result} event carrying the
 * status, the reply, the conversation id and the token usage — everything Forge records about a
 * turn arrives in that single line.
 */
@RequiredArgsConstructor
public final class AntigravityAgent implements Agent {

    private final Path exe;

    /** Model such as {@code gemini-3.1-pro-high}; {@code null} leaves the CLI's own default. */
    private final String model;

    public static AntigravityAgent create(String model) {
        return new AntigravityAgent(CliLocator.require(AgentKind.ANTIGRAVITY), model);
    }

    @Override
    public AgentKind kind() {
        return AgentKind.ANTIGRAVITY;
    }

    @Override
    public AgentResult run(AgentTask task) {
        Path log = task.logDir().resolve("antigravity.log");

        ProcResult proc = CliRunner.run(
                exe,
                AntigravityArgs.of(task.readOnly(), task.resume(),
                        task.model(), model, task.timeout()),
                task.cwd(),
                AntigravityArgs.stdinMessage(task.brief()),
                task.timeout(),
                log,
                SubscriptionOnly.API_KEYS);

        AgentResult result = Signals.resultOf(proc, finalMessage(proc));
        return result.withSession(conversationOf(proc, task.resume()));
    }

    /**
     * What the model said, or the error reported in its place.
     *
     * <p>Both arrive in the same {@code result} event: a refused turn carries {@code error} where a
     * successful one carries {@code response}. Reading the error out is what lets {@link Signals}
     * recognise a quota refusal rather than charging the project a repair round for it.
     */
    private String finalMessage(ProcResult proc) {
        JsonNode result = resultEvent(proc);
        if (result == null) {
            // No terminating event at all: killed, crashed, or refused before it started. The raw
            // output is more use to a person than an empty string, and Signals reads it too.
            return proc.combined().strip();
        }
        String error = result.path("error").asText("");
        if (!error.isBlank()) {
            return error;
        }
        return result.path("response").asText("").strip();
    }

    /**
     * The conversation to resume next time, falling back to the one we were continuing.
     *
     * <p>There is no flag to assign an id up front — the CLI names the conversation itself — so
     * like Codex, a turn killed before its first event leaves nothing to resume and the handoff
     * digest is what carries the work forward.
     */
    private String conversationOf(ProcResult proc, String resumed) {
        JsonNode result = resultEvent(proc);
        String reported = result == null ? "" : result.path("conversation_id").asText("");
        if (!reported.isBlank()) {
            return reported;
        }
        // The init event carries it too, and arrives first — so a turn that died mid-way still has
        // something to resume rather than starting the conversation again from the spec.
        return JsonlEvents.lastWhere(proc.stdout(), "event", "init")
                .map(node -> node.path("conversation_id").asText(""))
                .filter(id -> !id.isBlank())
                .orElse(resumed);
    }

    /** The single terminating event, which is where everything worth keeping lives. */
    private JsonNode resultEvent(ProcResult proc) {
        return JsonlEvents.lastWhere(proc.stdout(), "event", "result")
                .map(node -> node.get("result"))
                .filter(Objects::nonNull)
                .orElse(null);
    }

    /** Token usage for this turn, for the ledger that infers what a window costs. */
    public static Optional<JsonNode> usageOf(String stdout) {
        return JsonlEvents.lastWhere(stdout, "event", "result")
                .map(node -> node.path("result").path("usage"))
                .filter(node -> !node.isMissingNode() && !node.isNull());
    }
}
