package dev.fmangoni.forge.agent.model;

import java.nio.file.Path;
import java.time.Duration;

/**
 * One unit of work handed to an agent.
 *
 * @param cwd      the project directory; the agent is never given a path above this
 * @param brief    the full prompt, delivered on stdin
 * @param timeout  wall-clock cap for the invocation
 * @param logDir   where the transcript and any side files for this turn are written
 * @param readOnly when true the agent is launched without the tools that modify anything, for
 *                 turns that only need an answer — drafting a spec, reviewing a diff. A build turn
 *                 obviously cannot use this.
 * @param resume   an existing agent session to continue, or {@code null} to start a new one.
 *                 Resuming keeps the model's working context across turns, so a project spanning
 *                 several sessions does not re-read the spec and re-derive its design each time.
 * @param model    the model this turn should use, or blank to leave the CLI's own default
 */
public record AgentTask(Path cwd, String brief, Duration timeout, Path logDir,
                        boolean readOnly, String resume, String model) {

    /**
     * A turn that may modify the workspace, starting a fresh session.
     *
     * <p>Named, where this used to be the four-argument constructor you got by not thinking about
     * it. That default cost months: {@code ReviewStage} built its task that way, so every
     * cross-review ran with permission to edit the code it was judging — Claude with
     * {@code bypassPermissions} rather than the restricted pairing, and nothing ever noticed,
     * because a reviewer that behaves is indistinguishable from one that cannot misbehave.
     *
     * <p>Both kinds of turn are now equally deliberate. There is no shape of this call that grants
     * write access because somebody reached for the shortest constructor.
     */
    public static AgentTask writable(Path cwd, String brief, Duration timeout, Path logDir) {
        return new AgentTask(cwd, brief, timeout, logDir, false, null, "");
    }

    /** A turn that continues an existing session. */
    public AgentTask continuing(String sessionId) {
        return new AgentTask(cwd, brief, timeout, logDir, readOnly, sessionId, model);
    }

    /** The same turn, run on a particular model. */
    public AgentTask using(String modelName) {
        return new AgentTask(cwd, brief, timeout, logDir, readOnly, resume,
                modelName == null ? "" : modelName);
    }

    /** A turn that only produces an answer. */
    public static AgentTask readOnly(Path cwd, String brief, Duration timeout, Path logDir) {
        return new AgentTask(cwd, brief, timeout, logDir, true, null, "");
    }
}
