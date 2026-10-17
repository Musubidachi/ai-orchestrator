package dev.fmangoni.forge.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The clips a project still owes, as questions, and the sign of one feature chased under two names.
 *
 * <p>Built around 003 as it stood: "Garage customization" approved, renamed by the agent to "Garage
 * tuning", that approved too, and one test filming it under whichever name it was last told about.
 */
class MissingClipsTest {

    @TempDir
    Path repo;

    private static final Feature CUSTOMIZATION = new Feature("Garage customization", "");
    private static final Feature TUNING = new Feature("Garage tuning", "");
    private static final Feature RESET = new Feature("Empty garage reset", "");
    private static final Feature WAGER = new Feature("Pink slip wager", "");

    private Plan planWith(Feature... features) {
        Plan plan = new Plan();
        for (Feature feature : features) {
            plan.getFeatures().add(new PlanFeature(feature.name(), "click the thing"));
        }
        return plan;
    }

    private Decision approved(Feature feature) {
        return Decision.of(feature.id(), feature.name(), Features.APPROVED, "");
    }

    private void film(Feature feature) throws IOException {
        Path file = repo.resolve(feature.recordingPath(".webm"));
        Files.createDirectories(file.getParent());
        Clips.write(file);
    }

    @Test
    @DisplayName("an approved feature the agent stopped listing is asked about, with what it might be")
    void aRenamedFeatureIsAskedAbout() throws IOException {
        film(TUNING);
        film(WAGER);
        film(RESET);

        List<MissingClip> missing = MissingClips.of(repo, planWith(WAGER, TUNING, RESET),
                List.of(approved(CUSTOMIZATION), approved(WAGER), approved(TUNING), approved(RESET)));

        assertEquals(1, missing.size(), missing.toString());
        MissingClip clip = missing.get(0);
        assertEquals(CUSTOMIZATION.id(), clip.id());
        assertEquals("Garage customization", clip.name());
        assertFalse(clip.listed(), "the agent no longer lists it, which is the clue it was renamed");
        // Both garage features share a word with it; the agent's own order breaks the tie, and the
        // wager, which shares nothing, comes last rather than not at all.
        assertEquals(List.of("Garage tuning", "Empty garage reset", "Pink slip wager"), clip.sameAs());
        assertEquals("no recording was produced", clip.problem());
    }

    @Test
    @DisplayName("a feature the agent still lists, and simply did not film, says so")
    void aListedFeatureIsStillListed() throws IOException {
        film(WAGER);

        List<MissingClip> missing = MissingClips.of(repo, planWith(WAGER, TUNING),
                List.of(approved(WAGER), approved(TUNING)));

        assertEquals(1, missing.size());
        assertTrue(missing.get(0).listed());
        assertEquals(List.of("Pink slip wager"), missing.get(0).sameAs());
    }

    @Test
    @DisplayName("nothing approved, nothing asked: an unruled proposal owes no clip")
    void silenceAsksNothing() {
        assertTrue(MissingClips.of(repo, planWith(TUNING), List.of()).isEmpty());
    }

    @Test
    @DisplayName("a clip that moved from one approved name to another is caught")
    void aMovingClipIsCaught() {
        // Session 22 filmed it as customization, so tuning was missing; session 23 renamed the clip,
        // so customization was. The second gate is where a person should have been asked.
        List<Feature> owed = List.of(CUSTOMIZATION, TUNING);
        Optional<String> moved = MissingClips.moved(
                List.of(CUSTOMIZATION.id(), TUNING.id()), List.of(TUNING.id()),
                owed, List.of(take(CUSTOMIZATION)));

        assertTrue(moved.isPresent());
        assertTrue(moved.get().contains("\"Garage tuning\""), moved.get());
        assertTrue(moved.get().contains("\"Garage customization\""), moved.get());
        assertTrue(moved.get().contains("only a person can settle"), moved.get());
    }

    @Test
    @DisplayName("a feature approved since the last gate is missing, not moved")
    void aNewApprovalIsNotAMove() {
        // Tuning was fixed, and the wager is missing because it was only just asked for. Parking on
        // that would stop a project for doing exactly what it should.
        Optional<String> moved = MissingClips.moved(
                List.of(TUNING.id()), List.of(TUNING.id()),
                List.of(TUNING, WAGER), List.of(take(WAGER)));

        assertTrue(moved.isEmpty(), moved.toString());
    }

    @Test
    @DisplayName("a clip made, or a clip lost, on its own is not a move")
    void oneSidedChangesAreNotMoves() {
        List<Feature> owed = List.of(CUSTOMIZATION, TUNING);
        List<String> both = List.of(CUSTOMIZATION.id(), TUNING.id());

        assertTrue(MissingClips.moved(both, List.of(TUNING.id()), owed, List.of()).isEmpty(),
                "a missing clip that was made");
        assertTrue(MissingClips.moved(both, List.of(), owed, List.of(take(TUNING))).isEmpty(),
                "a clip lost with nothing made in its place");
        assertTrue(MissingClips.moved(both, List.of(TUNING.id()), owed, List.of(take(TUNING))).isEmpty(),
                "the same clip missing twice");
    }

    @Test
    @DisplayName("a missing clip that was declined since is not counted as made")
    void aDeclinedFeatureIsNotAFix() {
        // Customization was ruled out between the gates, so it is no longer owed. That is a person
        // settling it, not the agent filming it, and tuning missing now is not a move.
        Optional<String> moved = MissingClips.moved(
                List.of(CUSTOMIZATION.id(), TUNING.id()), List.of(CUSTOMIZATION.id()),
                List.of(TUNING), List.of(take(TUNING)));

        assertTrue(moved.isEmpty(), moved.toString());
    }

    private RecordingTake take(Feature feature) {
        return new RecordingTake(feature, null, Clip.bad("", "was never produced"));
    }
}
