package dev.fmangoni.forge.review.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonText;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * The cheap pass: one small call per claimed feature, on the cheapest model the reviewer has.
 *
 * <p>Why per feature rather than one call for all of them: the questions are independent, each one
 * is narrow enough for a small model to answer well, and a claim that goes unanswered is then
 * visibly unanswered rather than quietly dropped from a long reply. The cost is several calls, but
 * at the bottom of the ladder and with a prompt of a few hundred words each.
 *
 * <p>Never fatal, in either direction. A refused call leaves that feature {@code unchecked}, and
 * the expensive review still runs — this pass narrows that review, it does not gate it.
 */
@RequiredArgsConstructor
public final class FeatureReview {

    /** A small model reading for one feature. Longer than this is not thinking, it is wandering. */
    private static final Duration CAP = Duration.ofMinutes(4);

    /**
     * How many features one pass will check.
     *
     * <p>A handoff listing thirty features is itself a problem, and paying a call for each is not
     * the way to find that out. The rest are reported unchecked, which says so on the report.
     */
    static final int MAX_FEATURES = 12;

    private static final ObjectMapper MAPPER = Json.mapper();

    private final Agent reviewer;

    /** The reviewer's working directory: it reads the code that should contain the feature. */
    private final Path repo;

    private final Path logDir;

    /** The cheapest model the reviewing vendor offers; blank leaves the CLI default. */
    private final String model;

    /** What the agent's handoff claims this project does, in the order it claimed it. */
    public static List<PlanFeature> claimedBy(Plan plan) {
        if (plan == null || !plan.usable() || plan.getFeatures() == null) {
            return List.of();
        }
        return plan.getFeatures().stream()
                .filter(feature -> feature.getName() != null && !feature.getName().isBlank())
                .toList();
    }

    public List<FeatureCheck> run(Spec spec, List<PlanFeature> features) {
        List<FeatureCheck> checks = new ArrayList<>();
        String stopped = null;
        for (PlanFeature feature : features) {
            if (stopped != null) {
                // The vendor has already refused once. Asking again for every remaining feature
                // spends a call apiece to be told the same thing, and this pass exists to spend
                // less, so the rest are reported unchecked and the expensive review decides.
                checks.add(FeatureCheck.unchecked(feature.getName(), stopped));
                continue;
            }
            if (checks.size() >= MAX_FEATURES) {
                checks.add(FeatureCheck.unchecked(feature.getName(),
                        "not checked: only the first " + MAX_FEATURES + " claims are"));
                continue;
            }
            FeatureCheck check = check(spec, feature);
            checks.add(check);
            stopped = blockedReason(check);
        }
        return checks;
    }

    /** Set on a check the vendor refused, so the pass can stop rather than repeat the refusal. */
    private String blockedReason(FeatureCheck check) {
        return check.getWhy().startsWith(reviewer.kind() + " was unavailable") ? check.getWhy() : null;
    }

    private FeatureCheck check(Spec spec, PlanFeature feature) {
        AgentResult result = reviewer.run(
                AgentTask.readOnly(repo, FeatureBrief.of(feature, spec), CAP, logDir).using(model));

        if (result.blockedExternally()) {
            return FeatureCheck.unchecked(feature.getName(), reviewer.kind() + " was unavailable: " + result.outcome());
        }
        if (result.finalMessage() == null || result.finalMessage().isBlank()) {
            // Antigravity does this after a refused command: the session ends, successfully, with
            // nothing said. Three of 003.3's four checks went that way. Not a refusal of the vendor,
            // so the pass goes on; FeaturePass asks the next reviewer about this one.
            return FeatureCheck.unchecked(feature.getName(), reviewer.kind() + " ended without answering");
        }
        String json = JsonText.firstObject(result.finalMessage());
        if (json == null) {
            return FeatureCheck.unchecked(feature.getName(), "the reviewer did not answer in JSON");
        }
        try {
            FeatureCheck check = MAPPER.readValue(json, FeatureCheck.class);
            check.setFeature(feature.getName());
            if (!FeatureCheck.HOLDS.equals(check.getVerdict()) && !check.unproven()) {
                check.setVerdict(FeatureCheck.UNCHECKED);
            }
            return check;
        } catch (Exception e) {
            return FeatureCheck.unchecked(feature.getName(), "unreadable reply: " + e.getMessage());
        }
    }
}
