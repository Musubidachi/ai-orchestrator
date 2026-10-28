package dev.fmangoni.forge.review.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.plan.PlanFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cheap pass across reviewers: a claim one reviewer left unanswered goes to the next.
 *
 * <p>003.3 reached its big review with three of four claims unchecked, because Antigravity ended
 * those sessions without a word while Codex sat idle.
 */
class FeaturePassTest {

    @TempDir
    Path root;

    private Path repo;
    private Path logs;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(root.resolve("repo"));
        logs = Files.createDirectories(root.resolve("logs"));
    }

    /** A reviewer that records which claims it was asked about, as "kind:claim". */
    private final class Reviewer implements Agent {

        private final AgentKind kind;
        private final Function<String, String> reply;

        private Reviewer(AgentKind kind, Function<String, String> reply) {
            this.kind = kind;
            this.reply = reply;
        }

        @Override
        public AgentKind kind() {
            return kind;
        }

        @Override
        public AgentResult run(AgentTask task) {
            String claim = task.brief().contains("Report export") ? "export"
                    : task.brief().contains("Monthly summary") ? "summary" : "other";
            asked.add(kind + ":" + claim);
            return new AgentResult(AgentOutcome.COMPLETED, reply.apply(claim), null, null, null);
        }
    }

    private FeatureReview reviewer(AgentKind kind, Function<String, String> reply) {
        return new FeatureReview(new Reviewer(kind, reply), repo, logs, "cheapest");
    }

    private static PlanFeature feature(String name) {
        return new PlanFeature(name, "how you would see " + name);
    }

    private static final String HOLDS = "{\"verdict\":\"holds\",\"why\":\"Store.ts\"}";

    @Test
    @DisplayName("only the claims the first reviewer left unanswered are passed to the next")
    void unansweredClaimsArePassedOn() {
        FeaturePass pass = new FeaturePass(List.of(
                reviewer(AgentKind.ANTIGRAVITY, claim -> claim.equals("export") ? "" : HOLDS),
                reviewer(AgentKind.CODEX, claim -> HOLDS)));

        List<FeatureCheck> checks = pass.run(FeatureReviewTest.RACER,
                List.of(feature("Monthly summary"), feature("Report export")));

        assertEquals(List.of("ANTIGRAVITY:summary", "ANTIGRAVITY:export", "CODEX:export"), asked,
                "an answered claim is not bought twice");
        assertEquals(List.of("Monthly summary", "Report export"),
                checks.stream().map(FeatureCheck::getFeature).toList(), "kept in the order claimed");
        assertTrue(checks.stream().allMatch(FeatureCheck::answered));
    }

    @Test
    @DisplayName("when nobody answers, the claim stays unchecked with the last reason given")
    void nobodyAnswering() {
        FeaturePass pass = new FeaturePass(List.of(
                reviewer(AgentKind.ANTIGRAVITY, claim -> ""),
                reviewer(AgentKind.CODEX, claim -> "looks fine")));

        List<FeatureCheck> checks = pass.run(FeatureReviewTest.RACER, List.of(feature("Report export")));

        assertEquals(2, asked.size());
        assertEquals(FeatureCheck.UNCHECKED, checks.get(0).getVerdict(), "never read as holding up");
    }

    @Test
    @DisplayName("once every claim is answered, later reviewers are not asked at all")
    void stopsWhenAnswered() {
        new FeaturePass(List.of(reviewer(AgentKind.CODEX, claim -> HOLDS), reviewer(AgentKind.CLAUDE, claim -> HOLDS)))
                .run(FeatureReviewTest.RACER, List.of(feature("Monthly summary")));

        assertEquals(List.of("CODEX:summary"), asked);
    }

    @Test
    @DisplayName("claims past the bound are never passed on, and say why they were not checked")
    void theBoundHoldsAcrossReviewers() {
        List<PlanFeature> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(feature("feature " + i));
        }

        List<FeatureCheck> checks = new FeaturePass(List.of(
                reviewer(AgentKind.ANTIGRAVITY, claim -> ""),
                reviewer(AgentKind.CODEX, claim -> "")))
                .run(FeatureReviewTest.RACER, many);

        assertEquals(2 * FeatureReview.MAX_FEATURES, asked.size());
        assertEquals(15, checks.size());
        assertTrue(checks.get(14).getWhy().contains("only the first"), checks.get(14).getWhy());
    }
}
