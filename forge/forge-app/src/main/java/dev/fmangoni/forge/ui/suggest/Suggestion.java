package dev.fmangoni.forge.ui.suggest;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.ui.editor.Draft;

/**
 * @param agent  the vendor to ask
 * @param result the proposal, or the reason there isn't one
 */
public record Suggestion(String agent, Draft draft, String error,
                         String complexity, String complexityReason) {

    static Suggestion failed(AgentKind kind, String error) {
        return new Suggestion(kind.name(), null, error, "", "");
    }
}
