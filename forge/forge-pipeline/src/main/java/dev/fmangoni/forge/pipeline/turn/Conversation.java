package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.state.RunState;
import lombok.experimental.UtilityClass;

/**
 * The vendor conversation a project's turns resume, held per vendor.
 *
 * <p>Per vendor because a project built by one model is reviewed by the other, and their session
 * namespaces are unrelated.
 */
@UtilityClass
class Conversation {

    String sessionFor(RunState state, AgentKind kind) {
        return kind == AgentKind.CLAUDE ? state.getClaudeSession() : state.getCodexSession();
    }

    /** Keeps the vendor's session id so the next turn resumes the same conversation. */
    void remember(RunState state, AgentKind kind, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (kind == AgentKind.CLAUDE) {
            state.setClaudeSession(sessionId);
        } else {
            state.setCodexSession(sessionId);
        }
    }

    /**
     * Drops the vendor conversation so the next turn starts fresh from the digest.
     *
     * <p>The saving is not incidental: a resumed turn re-sends the entire transcript, so on a long
     * project most of what each call pays for is history it has already acted on. See
     * {@link Compaction} for when it is safe.
     */
    void compactIfDue(RunState state, int every) {
        if (!Compaction.due(state, every)) {
            return;
        }
        state.setClaudeSession(null);
        state.setCodexSession(null);
        state.setSessionsOnConversation(0);
        state.setCompactions(state.getCompactions() + 1);
    }
}
