package dev.fmangoni.forge.spec.model;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One wishlist entry: the YAML contract plus the prose describing what to build.
 *
 * @param source where it was read from, so the report can point back at it
 * @param front  the machine-readable contract
 * @param body   everything below the front matter, handed to the agent verbatim
 */
public record Spec(Path source, FrontMatter front, String body) {

    public String id() {
        return front.getId();
    }

    public String title() {
        return front.getTitle();
    }

    public List<String> acceptance() {
        return front.getAcceptance();
    }

    /** The project this improves, if any. */
    public Optional<String> parentId() {
        String parent = front.getParent();
        return parent == null || parent.isBlank() ? Optional.empty() : Optional.of(parent.trim());
    }

    public boolean isImprovement() {
        return parentId().isPresent();
    }

    public List<String> evidence() {
        return front.getEvidence();
    }

    /** The model this spec asks of a vendor, or empty to leave the CLI's default alone. */
    public String modelFor(AgentKind kind) {
        // Exhaustive, with no default branch. The previous ternary answered "Codex" for every
        // vendor that was not Claude, so a third one would silently have been given Codex's model
        // slug — a wrong model name is rejected by the CLI, but only after the call is made.
        String model = switch (kind) {
            case CLAUDE -> front.getClaudeModel();
            case CODEX -> front.getCodexModel();
            case ANTIGRAVITY -> front.getAntigravityModel();
        };
        return model == null ? "" : model.trim();
    }

    public int priority() {
        return front.getPriority();
    }

    public int maxRepairRounds() {
        return front.getMaxRepairRounds();
    }

    public int maxSessions() {
        return front.getMaxSessions();
    }

    public int compactAfterSessions() {
        return front.getCompactAfterSessions();
    }

    public Duration agentTimeout() {
        return Duration.ofMinutes(front.getAgentTimeoutMinutes());
    }

    public Duration gateTimeout() {
        return Duration.ofMinutes(front.getGateTimeoutMinutes());
    }

    /** The command that runs the built app locally, with {@code {port}} still in it. */
    public Optional<String> serve() {
        String serve = front.getServe();
        return serve == null || serve.isBlank() ? Optional.empty() : Optional.of(serve.trim());
    }

    /** The picked design this enhancement restyles its parent to, when it was built to one. */
    public Optional<String> design() {
        String design = front.getDesign();
        return design == null || design.isBlank() ? Optional.empty() : Optional.of(design.trim());
    }

    /** The kind of extra this enhancement adds beside its parent's look, when it adds one. */
    public Optional<String> extra() {
        String extra = front.getExtra();
        return extra == null || extra.isBlank() ? Optional.empty() : Optional.of(extra.trim());
    }

    /** The page of the served app to look at, always starting with a slash. */
    public String servePath() {
        String path = front.getServePath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path.trim() : "/" + path.trim();
    }

    public Optional<DeploySettings> deploy() {
        return Optional.ofNullable(front.getDeploy());
    }

    /** {@code none}, {@code test} or {@code live}; {@code none} when the spec says nothing. */
    public String adsMode() {
        MonetizationSettings money = front.getMonetization();
        return money == null || money.getAds() == null || money.getAds().isBlank()
                ? "none" : money.getAds().trim().toLowerCase(Locale.ROOT);
    }

    /** Empty when the spec says {@code auto}, leaving the choice to the queue balancer. */
    public Optional<AgentKind> assignedAgent() {
        String assign = front.getAssign();
        if (assign == null || assign.isBlank() || "auto".equalsIgnoreCase(assign.trim())) {
            return Optional.empty();
        }
        return Optional.of(AgentKind.parse(assign));
    }
}
