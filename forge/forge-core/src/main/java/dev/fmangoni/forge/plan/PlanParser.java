package dev.fmangoni.forge.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.JsonText;
import lombok.experimental.UtilityClass;

/**
 * Pulls the handoff out of an agent's closing message.
 *
 * <p>Never throws. A turn that did real work but ended with a badly formed handoff must not be
 * treated as a failure — the code it wrote is on disk either way, and the gate is what judges it.
 * A missing plan costs the next session some context, nothing more.
 */
@UtilityClass
public class PlanParser {

    private final ObjectMapper JSON = new ObjectMapper();

    public Plan parse(String finalMessage) {
        if (finalMessage == null || finalMessage.isBlank()) {
            return Plan.failed("the agent returned no closing message");
        }
        String json = lastObject(finalMessage);
        if (json == null) {
            return Plan.failed("no handoff JSON in the agent's closing message");
        }
        try {
            return JSON.readValue(json, Plan.class);
        } catch (Exception e) {
            return Plan.failed("handoff JSON was malformed: " + e.getMessage());
        }
    }

    /**
     * Takes the <em>last</em> JSON object in the message.
     *
     * <p>The brief asks for the handoff at the end, and agents habitually quote example JSON —
     * config snippets, test fixtures — earlier in a summary. Taking the first object would
     * reliably pick up the example instead of the answer.
     */
    private String lastObject(String text) {
        int from = text.lastIndexOf('{');
        while (from >= 0) {
            String candidate = JsonText.firstObject(text.substring(from));
            if (candidate != null && candidate.contains("\"status\"")) {
                return candidate;
            }
            from = text.lastIndexOf('{', from - 1);
        }
        return JsonText.firstObject(text);
    }
}
