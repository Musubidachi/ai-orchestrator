package dev.fmangoni.forge.agent.vendor;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.agent.stream.JsonlEvents;
import dev.fmangoni.forge.agent.stream.Signals;
import dev.fmangoni.forge.agent.vendor.model.CodexArgs;
import dev.fmangoni.forge.agent.vendor.model.CodexOptions;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;

/**
 * Drives the Codex CLI headlessly.
 *
 * <p>The command line itself is {@link CodexArgs}, which is where the flags and the reasons for
 * them are recorded. This class is only the invocation and the reading of what came back.
 *
 * <p>{@code approval_policy} is left at its default: setting it would mean passing a TOML value
 * containing quotes through {@code cmd.exe}, and {@code exec} is already non-interactive.
 */
@RequiredArgsConstructor
public final class CodexAgent implements Agent {

    private final Path exe;

    /** Model slug such as {@code gpt-6-astra}; {@code null} leaves the user's configured default. */
    private final String model;

    /** The sandbox to use and the features to leave out, as {@code vendors.json} says. */
    private final CodexOptions options;

    public static CodexAgent create(String model) {
        return create(model, CodexOptions.none());
    }

    public static CodexAgent create(String model, CodexOptions options) {
        return new CodexAgent(CliLocator.require(AgentKind.CODEX), model, options);
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CODEX;
    }

    @Override
    public AgentResult run(AgentTask task) {
        Path log = task.logDir().resolve("codex.log");
        Path lastMessage = task.logDir().resolve("codex-last-message.txt");
        // Cleared first, because Codex only writes this file when a turn produces a closing
        // message. A refused call leaves the previous turn's there, and it was read back as this
        // one's: the quota ledger recorded "status: complete … all acceptance commands pass" as the
        // reason Codex was blocked, and a call that did nothing could hand the orchestrator the last
        // session's finished plan as its own.
        try {
            Files.deleteIfExists(lastMessage);
        } catch (IOException e) {
            // If it cannot be removed it may be stale; the event stream below is the fallback.
            System.err.println("could not clear " + lastMessage + ": " + e.getMessage());
        }

        ProcResult proc = CliRunner.run(
                exe, CodexArgs.of(task.cwd(), lastMessage, task.readOnly(), task.resume(),
                        task.model(), model, options),
                task.cwd(), task.brief(), task.timeout(), log, SubscriptionOnly.API_KEYS);

        AgentResult result = Signals.resultOf(proc, readLastMessage(lastMessage, proc));
        return result.withSession(sessionOf(proc, task.resume()));
    }

    /**
     * Codex names the session in its opening event; on a resumed turn we already know it.
     *
     * <p>Unlike Claude there is no flag to assign the id up front, so a turn killed before that
     * first event simply has no session to resume — the handoff plan is the fallback.
     */
    private String sessionOf(ProcResult proc, String resumed) {
        if (resumed != null) {
            return resumed;
        }
        return JsonlEvents.lastOf(proc.stdout(), "thread.started", "thread_id").orElse(null);
    }

    /** Codex writes its closing message to the {@code -o} file; the event stream is the fallback. */
    private String readLastMessage(Path file, ProcResult proc) {
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException ignored) {
            // Fall through to the event stream below.
        }
        return JsonlEvents.firstError(proc.stdout()).orElse("");
    }
}
