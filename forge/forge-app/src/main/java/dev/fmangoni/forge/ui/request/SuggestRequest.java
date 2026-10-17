package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.agent.model.AgentKind;
import lombok.Data;

/** A request from the editor to draft a spec from a rough description. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuggestRequest {

    private String description = "";

    /** Which vendor to ask. Unrecognised values fall back rather than failing the request. */
    private String agent = "codex";

    public AgentKind agentKind() {
        try {
            return AgentKind.parse(agent);
        } catch (RuntimeException e) {
            return AgentKind.CODEX;
        }
    }
}
