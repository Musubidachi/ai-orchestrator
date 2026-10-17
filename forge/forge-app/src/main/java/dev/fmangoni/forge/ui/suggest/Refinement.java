package dev.fmangoni.forge.ui.suggest;

import dev.fmangoni.forge.agent.model.AgentKind;

/**
 * One rewritten section, and the Notes reconciled with it.
 *
 * @param notes the whole Notes section when the change could have invalidated it, else blank
 */
public record Refinement(String agent, String section, String notes,
                         String whatChanged, String error) {

    static Refinement failed(AgentKind kind, String error) {
        return new Refinement(kind.name(), "", "", "", error);
    }
}
