package dev.fmangoni.forge.security.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.json.JsonText;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AI layer: a model other than the builder reads the change and the scanners' results.
 *
 * <p>Reviewers are tried in the same order and with the same fall-through as the cross-review: one
 * that cannot be reached, or whose reply cannot be read, hands over to the next. If none can answer,
 * the layer says so and the others stand on their own — a quota window elsewhere is not a reason to
 * hold the project, and the cross-review that follows will suspend it properly if nobody is there.
 */
public final class SecurityReview {

    public static final String LAYER = "AI security review";

    /** Reading, not building: longer than this has wandered off the question. */
    private static final Duration CAP = Duration.ofMinutes(10);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Agents agents;
    private final Path repo;
    private final Path logDir;

    public SecurityReview(Agents agents, Path repo, Path logDir) {
        this.agents = agents;
        this.repo = repo;
        this.logDir = logDir;
    }

    public LayerResult run(Spec spec, AgentKind builder, String diff, List<SecurityFinding> scanned) {
        String brief = SecurityBrief.of(spec, diff, scanned);
        List<String> why = new ArrayList<>();
        for (AgentKind kind : agents.reviewersFor(builder)) {
            Agent reviewer = agents.get(kind);
            AgentResult result = reviewer.run(AgentTask.readOnly(repo, brief, CAP, logDir)
                    .using(spec.modelFor(kind)));
            if (result.blockedExternally()) {
                why.add(kind + " " + result.outcome());
                continue;
            }
            List<SecurityFinding> findings = parse(result.finalMessage());
            if (findings == null) {
                why.add(kind + " replied with nothing readable");
                continue;
            }
            return LayerResult.ran(LAYER, "read by " + kind, findings);
        }
        return why.isEmpty()
                ? LayerResult.skipped(LAYER, "no model other than the builder is installed")
                : LayerResult.skipped(LAYER, "no reviewer could answer: " + String.join("; ", why));
    }

    /** The findings, or null when the reply holds no usable JSON. */
    static List<SecurityFinding> parse(String reply) {
        String json = JsonText.firstObject(reply);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.has("findings")) {
                return null;
            }
            List<SecurityFinding> out = new ArrayList<>();
            for (JsonNode node : root.path("findings")) {
                String severity = switch (node.path("severity").asText("").toLowerCase(Locale.ROOT)) {
                    case "critical" -> SecurityFinding.CRITICAL;
                    case "high" -> SecurityFinding.HIGH;
                    case "low" -> SecurityFinding.LOW;
                    default -> SecurityFinding.MEDIUM;
                };
                boolean confirmed = node.path("confirmed").asBoolean(false);
                String cwe = node.path("cwe").asText("");
                String detail = node.path("detail").asText("");
                out.add(new SecurityFinding(LAYER, severity, node.path("title").asText("(untitled)"),
                        node.path("file").asText(""),
                        (cwe.isBlank() ? "" : cwe + ": ") + detail + (confirmed ? "" : " (not confirmed)"),
                        confirmed && (severity.equals(SecurityFinding.CRITICAL) || severity.equals(SecurityFinding.HIGH))));
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
