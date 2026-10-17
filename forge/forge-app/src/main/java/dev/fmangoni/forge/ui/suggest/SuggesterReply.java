package dev.fmangoni.forge.ui.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.agent.model.AgentKind;

/** What one vendor said: the JSON object in its reply, or why there is none. */
record SuggesterReply(AgentKind kind, JsonNode json, String error) {

    boolean answered() {
        return json != null;
    }
}
