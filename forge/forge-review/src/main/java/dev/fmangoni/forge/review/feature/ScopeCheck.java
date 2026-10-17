package dev.fmangoni.forge.review.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonText;
import java.nio.file.Path;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

/**
 * One question about a visual polish, on the cheapest model the reviewer has: did it change anything
 * besides appearance?
 *
 * <p>A polish is asked to restyle and nothing else, and reading the whole project again on the best
 * model to confirm that is the expensive way to find out. But an agent restyling a screen can also
 * tidy away a rule, a control or a feature, so "it was only visual" is asked of the diff rather than
 * taken from the agent. A yes skips the full review; a no, or no answer, leaves it to run as usual.
 */
@RequiredArgsConstructor
public final class ScopeCheck {

    /** One narrow question. Longer than this is not reading, it is wandering. */
    private static final Duration CAP = Duration.ofMinutes(4);

    private static final ObjectMapper MAPPER = Json.mapper();

    private final Agent reviewer;
    private final Path repo;
    private final Path logDir;

    /** The cheapest model the reviewing vendor offers; blank leaves the CLI default. */
    private final String model;

    public ScopeVerdict run(String specTitle, String diff) {
        AgentResult result = reviewer.run(
                AgentTask.readOnly(repo, brief(specTitle, diff), CAP, logDir).using(model));
        if (result.blockedExternally()) {
            return ScopeVerdict.unreached(reviewer.kind() + " was unavailable: " + result.outcome());
        }
        String json = JsonText.firstObject(result.finalMessage());
        if (json == null) {
            return ScopeVerdict.unreached("the reviewer did not answer in JSON");
        }
        try {
            JsonNode answer = MAPPER.readTree(json);
            if (!answer.path("appearance_only").isBoolean()) {
                return ScopeVerdict.unreached("the reviewer's answer had no appearance_only");
            }
            return new ScopeVerdict(true, answer.path("appearance_only").asBoolean(),
                    answer.path("why").asText("").strip());
        } catch (Exception e) {
            return ScopeVerdict.unreached("unreadable reply: " + e.getMessage());
        }
    }

    static String brief(String specTitle, String diff) {
        return """
                A change was made to "%s" to restyle it: to make it look like a design a person \
                picked, and to change nothing else. The diff is below.

                Answer one question: does this change anything other than appearance?

                Appearance is colours, fonts, spacing, sizes, layout, images, icons, animation, and \
                the wording of labels where the meaning is unchanged. Everything else counts as more \
                than appearance: a rule or a calculation, what a control does, what is saved or \
                loaded, timings, a feature removed, hidden or made unreachable, text a person relies \
                on for meaning, network calls, scripts, ads, or a new dependency.

                You may read files in the repository if the diff alone does not settle it.

                Reply with one JSON object and nothing else:
                {"appearance_only": true or false, "why": "one sentence: what changed beyond \
                appearance, or 'only appearance changed'"}

                DIFF:
                %s
                """.formatted(specTitle, diff);
    }
}
