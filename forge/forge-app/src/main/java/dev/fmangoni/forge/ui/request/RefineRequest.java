package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.agent.model.AgentKind;
import lombok.Data;

/**
 * A request to rewrite one section of a description.
 *
 * <p>Carries all three sections, not only the one being changed. The editor holds unsaved text, so
 * the sections here are what the author is looking at rather than what is on disk — refining
 * against the file would quietly discard whatever they had just typed.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefineRequest {

    private String title = "";
    private String goal = "";
    private String nonGoals = "";
    private String notes = "";

    /** Which section to rewrite: Goal, Non-goals or Notes. */
    private String target = "";

    /** What the author wants different about it; optional. */
    private String feedback = "";

    /** Which vendor to ask. Unrecognised values fall back rather than failing the request. */
    private String agent = "claude";

    public AgentKind agentKind() {
        try {
            return AgentKind.parse(agent);
        } catch (RuntimeException e) {
            return AgentKind.CLAUDE;
        }
    }
}
