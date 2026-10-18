package dev.fmangoni.forge.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What Forge actually asks a reviewer to do.
 *
 * <p>Written after finding that it had been asking for the wrong thing since the review stage was
 * built. {@code new AgentTask(...)} defaults to a writable turn and the review used it, so every
 * cross-review ran with permission to change the code it was judging — Claude with
 * {@code bypassPermissions} instead of the restricted pairing, Antigravity in {@code accept-edits}
 * instead of {@code plan}. Nothing had noticed because a reviewer that behaves itself is
 * indistinguishable from one that cannot misbehave.
 *
 * <p>That is the difference this file exists to keep: not whether the reviewer wrote anything on
 * one occasion, but whether it was ever given the option.
 */
class ReviewStageTest {

    @TempDir
    Path root;

    private Path repo;
    private Path logs;
    private Path reviewFile;

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(root.resolve("repo"));
        logs = Files.createDirectories(root.resolve("logs"));
        reviewFile = root.resolve("review.json");
    }

    /** Captures the task rather than performing it, which is the whole point of the fixture. */
    private record Spy(AtomicReference<AgentTask> seen, String reply) implements Agent {
        @Override
        public AgentKind kind() {
            return AgentKind.CODEX;
        }

        @Override
        public AgentResult run(AgentTask task) {
            seen.set(task);
            return new AgentResult(AgentOutcome.COMPLETED, reply, null, null, null);
        }
    }

    private Spec spec() throws IOException {
        Path file = root.resolve("001-thing.md");
        Files.writeString(file, """
                ---
                id: "001"
                title: A thing
                acceptance:
                  - echo ok
                ---

                ## Goal
                Build the thing.
                """, StandardCharsets.UTF_8);
        return SpecLoader.load(file);
    }

    private AgentTask taskFor(String reply) throws IOException {
        AtomicReference<AgentTask> seen = new AtomicReference<>();
        new ReviewStage(new Spy(seen, reply), repo, logs, reviewFile, "")
                .review(spec(), "diff --git a/x b/x");
        return seen.get();
    }

    @Test
    @DisplayName("a reviewer is handed a read-only turn")
    void reviewersGetNoWritePermission() throws IOException {
        // The regression, in one assertion. A model asked to find problems is one prompt away from
        // deciding to fix one, and a reviewer that edits the diff it is judging has destroyed the
        // only independent opinion in the pipeline.
        assertTrue(taskFor("{\"findings\":[]}").readOnly(),
                "the cross-review was given permission to modify the code it is reviewing");
    }

    @Test
    @DisplayName("a reviewer is confined to the project's own repository")
    void reviewersSeeOnlyTheRepo() throws IOException {
        assertEquals(repo, taskFor("{\"findings\":[]}").cwd());
    }

    @Test
    @DisplayName("a reviewer starts a fresh session rather than resuming the builder's")
    void reviewsDoNotInheritAConversation() throws IOException {
        // Resuming would hand the reviewer the builder's reasoning, which is the one thing a
        // second opinion must not start from.
        assertFalse(taskFor("{\"findings\":[]}").resume() != null,
                "a review must not continue the conversation that produced the code");
    }

    @Test
    @DisplayName("what the cheap pass could not find is handed over as leads, stated as unconfirmed")
    void unprovenClaimsBecomeLeads() throws IOException {
        AtomicReference<AgentTask> seen = new AtomicReference<>();
        new ReviewStage(new Spy(seen, "{\"findings\":[]}"), repo, logs, reviewFile, "best")
                .review(spec(), "diff --git a/x b/x", List.of(
                        new FeatureCheck("Monthly summary", FeatureCheck.UNPROVEN, "Export.java only logs a TODO"),
                        new FeatureCheck("Report export", FeatureCheck.HOLDS, "Report.java"),
                        FeatureCheck.unchecked("Leaderboard", "out of quota")));

        String brief = seen.get().brief();
        assertTrue(brief.contains("Monthly summary"), brief);
        assertTrue(brief.contains("Export.java only logs a TODO"));
        assertFalse(brief.contains("Report export"), "a claim that held up is not a lead");
        assertFalse(brief.contains("Leaderboard"), "an unchecked claim is not evidence of anything");
        assertTrue(brief.contains("It may simply have missed them"),
                "a weak model's miss must not be handed over as fact");
    }

    @Test
    @DisplayName("every check is kept on the review, so the report can say what was looked at")
    void checksArePersisted() throws IOException {
        List<FeatureCheck> checks = List.of(
                new FeatureCheck("Monthly summary", FeatureCheck.UNPROVEN, "not found"),
                new FeatureCheck("Report export", FeatureCheck.HOLDS, "Report.java"));

        Review review = new ReviewStage(new Spy(new AtomicReference<>(), "{\"findings\":[]}"),
                repo, logs, reviewFile, "best").review(spec(), "diff", checks);

        assertEquals(2, review.getFeatureChecks().size());
        assertEquals(List.of("Monthly summary"),
                review.unprovenFeatures().stream().map(FeatureCheck::getFeature).toList());
        assertEquals(2, ReviewStore.load(reviewFile).getFeatureChecks().size(),
                "and survives the round trip through review.json");
    }

    @Test
    @DisplayName("a review of a project with no claimed features reads exactly as it always did")
    void noChecksNoSection() throws IOException {
        assertFalse(taskFor("{\"findings\":[]}").brief().contains("could not confirm"));
    }
}
