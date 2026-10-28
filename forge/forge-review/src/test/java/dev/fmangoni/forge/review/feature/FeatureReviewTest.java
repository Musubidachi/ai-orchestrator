package dev.fmangoni.forge.review.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cheap pass: one small call per claimed feature, before the expensive review.
 *
 * <p>What it must never do is cost more than it saves. A vendor that refuses the first call refuses
 * the rest, and asking anyway would spend a call per feature to be told so — which is why the
 * refusal stops the pass rather than being recorded once per claim.
 */
class FeatureReviewTest {

    static final Spec RACER = spec("Racer", null);

    /** A spec with this title; a parent makes it an enhancement of that project. */
    static Spec spec(String title, String parent) {
        FrontMatter front = new FrontMatter();
        front.setId(parent == null ? "003" : parent + ".1");
        front.setTitle(title);
        front.setParent(parent);
        return new Spec(Path.of(front.getId() + ".md"), front, "the spec");
    }

    @TempDir
    Path root;

    private Path repo;
    private Path logs;
    private final List<AgentTask> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(root.resolve("repo"));
        logs = Files.createDirectories(root.resolve("logs"));
    }

    /** Answers each call from the feature named in the brief, and records what it was asked. */
    private final class Replier implements Agent {

        private final Function<AgentTask, AgentResult> reply;

        private Replier(Function<AgentTask, AgentResult> reply) {
            this.reply = reply;
        }

        @Override
        public AgentKind kind() {
            return AgentKind.CODEX;
        }

        @Override
        public AgentResult run(AgentTask task) {
            seen.add(task);
            return reply.apply(task);
        }
    }

    private static AgentResult says(String reply) {
        return new AgentResult(AgentOutcome.COMPLETED, reply, null, null, null);
    }

    private static AgentResult rateLimited() {
        return new AgentResult(AgentOutcome.RATE_LIMITED, "usage limit", null,
                Instant.now().plusSeconds(3600), null);
    }

    private static PlanFeature feature(String name) {
        return new PlanFeature(name, "how you would see " + name);
    }

    private FeatureReview review(Function<AgentTask, AgentResult> reply) {
        return new FeatureReview(new Replier(reply), repo, logs, "cheapest");
    }

    @Test
    @DisplayName("each claim is asked about once, read-only, on the model it was given")
    void oneReadOnlyCallPerClaim() {
        List<FeatureCheck> checks = review(task -> says("{\"verdict\":\"holds\",\"why\":\"Report.java\"}"))
                .run(RACER,List.of(feature("Report export"), feature("Monthly summary")));

        assertEquals(2, seen.size());
        assertTrue(seen.stream().allMatch(AgentTask::readOnly),
                "a reviewer must never be able to change the code it is judging");
        assertTrue(seen.stream().allMatch(task -> "cheapest".equals(task.model())));
        assertTrue(seen.get(0).brief().contains("Report export"));
        assertFalse(seen.get(0).brief().contains("Monthly summary"), "one claim per call, kept small");
        assertEquals(List.of("Report export", "Monthly summary"),
                checks.stream().map(FeatureCheck::getFeature).toList());
        assertTrue(checks.stream().allMatch(check -> FeatureCheck.HOLDS.equals(check.getVerdict())));
    }

    @Test
    @DisplayName("a claim the reviewer cannot find comes back unproven, with where it looked")
    void unprovenIsReported() {
        List<FeatureCheck> checks = review(task ->
                says("Here you go:\n```json\n{\"verdict\":\"unproven\",\"why\":\"Report.java only has a stub\"}\n```"))
                .run(RACER,List.of(feature("Report export")));

        assertTrue(checks.get(0).unproven());
        assertEquals("Report.java only has a stub", checks.get(0).getWhy());
    }

    @Test
    @DisplayName("one refusal stops the pass: the rest are unchecked, not asked again")
    void aRefusalStopsThePass() {
        List<FeatureCheck> checks = review(task -> rateLimited())
                .run(RACER,List.of(feature("one"), feature("two"), feature("three")));

        assertEquals(1, seen.size(), "the other two would have been refused the same way");
        assertEquals(3, checks.size(), "every claim is still accounted for");
        assertTrue(checks.stream().allMatch(check -> FeatureCheck.UNCHECKED.equals(check.getVerdict())));
        assertTrue(checks.get(2).getWhy().contains("unavailable"), checks.get(2).getWhy());
    }

    @Test
    @DisplayName("a reply that is not JSON, or a verdict nobody recognises, is unchecked rather than a pass")
    void unreadableRepliesAreNotAPass() {
        assertEquals(FeatureCheck.UNCHECKED,
                review(task -> says("looks fine to me")).run(RACER,List.of(feature("one"))).get(0).getVerdict());
        assertEquals(FeatureCheck.UNCHECKED,
                review(task -> says("{\"verdict\":\"probably\",\"why\":\"\"}"))
                        .run(RACER,List.of(feature("one"))).get(0).getVerdict(),
                "an unknown verdict must not be read as the feature holding up");
    }

    @Test
    @DisplayName("an empty reply is no answer, and does not stop the pass the way a refusal does")
    void anEmptyReplyIsNoAnswer() {
        List<FeatureCheck> checks = review(task -> says(task.brief().contains("Report export") ? "" : "{\"verdict\":\"holds\",\"why\":\"x\"}"))
                .run(RACER, List.of(feature("Report export"), feature("Monthly summary")));

        assertEquals(2, seen.size(), "Antigravity ends quietly after a refused command; the next claim is still asked");
        assertFalse(checks.get(0).answered());
        assertTrue(checks.get(0).getWhy().contains("ended without answering"), checks.get(0).getWhy());
        assertTrue(checks.get(1).answered());
    }

    @Test
    @DisplayName("an enhancement's claims are judged against IMPROVEMENT.md, not the original's non-goals")
    void anEnhancementIsJudgedByWhatItAsked() {
        review(task -> says("{\"verdict\":\"holds\",\"why\":\"x\"}"))
                .run(spec("Racer: shop", "003"), List.of(feature("Report export")));
        review(task -> says("{\"verdict\":\"holds\",\"why\":\"x\"}"))
                .run(RACER, List.of(feature("Drag race")));

        assertTrue(seen.get(0).brief().contains("IMPROVEMENT.md wins"), seen.get(0).brief());
        assertFalse(seen.get(1).brief().contains("IMPROVEMENT.md"), "a first build has no IMPROVEMENT.md");
        assertTrue(seen.get(1).brief().contains("do not run any command"));
    }

    @Test
    @DisplayName("a handoff listing dozens of features does not buy dozens of calls")
    void thePassIsBounded() {
        List<PlanFeature> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(feature("feature " + i));
        }

        List<FeatureCheck> checks = review(task -> says("{\"verdict\":\"holds\",\"why\":\"x\"}"))
                .run(RACER,many);

        assertEquals(12, seen.size());
        assertEquals(20, checks.size());
        assertEquals(FeatureCheck.UNCHECKED, checks.get(19).getVerdict());
    }

    @Test
    @DisplayName("claims come from the handoff, and a handoff without one is simply no pass")
    void claimsComeFromTheHandoff() {
        Plan plan = new Plan();
        plan.setSummary("built the racer");
        plan.setFeatures(List.of(feature("Report export"), new PlanFeature("", "unnamed")));

        assertEquals(List.of("Report export"),
                FeatureReview.claimedBy(plan).stream().map(PlanFeature::getName).toList());
        assertEquals(List.of(), FeatureReview.claimedBy(null));
        assertEquals(List.of(), FeatureReview.claimedBy(new Plan()), "an unusable handoff claims nothing");
    }
}
