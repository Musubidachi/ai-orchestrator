package dev.fmangoni.forge.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the project owes a recording of.
 *
 * <p>The rule the whole mechanism rests on is that an obligation appears only when a person says
 * yes. The agent proposes; silence means nothing is owed; a declined feature stays declined. Get
 * that wrong in either direction and it fails badly — approving by default would park every
 * project on unfilmed features, and approving nothing would leave the gate check dead code.
 */
class FeaturesTest {

    @TempDir
    Path repo;

    private Plan planWith(String... names) {
        Plan plan = new Plan();
        for (String name : names) {
            plan.getFeatures().add(new PlanFeature(name, "click the thing"));
        }
        return plan;
    }

    private Decision ruling(Feature feature, String verdict) {
        return Decision.of(feature.id(), feature.name(), verdict, "");
    }

    private void record(Feature feature, String extension) throws IOException {
        Path file = repo.resolve(feature.recordingPath(extension));
        Files.createDirectories(file.getParent());
        // A real animated GIF under every extension: the format checks live in RecordingTest, and
        // what is being tested here is which features are owed one, not what a WebM looks like.
        Clips.write(file);
    }

    // ------------------------------------------------------------- identity

    @Test
    @DisplayName("a feature is identified by a name a person can read in a directory listing")
    void slugIsReadable() {
        assertEquals("feature-monthly-summary", new Feature("Monthly summary", "").id());
        assertEquals("artifacts/feature-monthly-summary.webm",
                new Feature("Monthly summary", "").recordingPath(".webm"));
    }

    @Test
    @DisplayName("punctuation and case do not change which feature it is")
    void slugIsStable() {
        assertEquals(new Feature("Export start & totals", "").id(),
                new Feature("export start and totals", "").id().replace("-and-", "-"));
    }

    @Test
    @DisplayName("renaming a feature makes it a new one, so an old approval does not carry over")
    void renamingBreaksIdentity() {
        // The same rule the review items follow, for the same reason: approving "Report export"
        // is not approving whatever the agent decides to call it next session.
        assertFalse(new Feature("Report export", "").id()
                .equals(new Feature("Report export and totals", "").id()));
    }

    @Test
    @DisplayName("a feature with no usable name still has a filename")
    void unnamedFeaturesStillResolve() {
        assertEquals("feature-unnamed", new Feature("!!!", "").id());
    }

    // ------------------------------------------------------------- proposals

    @Test
    @DisplayName("a handoff that proposed nothing owes nothing")
    void noProposalsNoObligations() {
        assertTrue(Feature.of(new Plan()).isEmpty());
        assertTrue(Feature.of(null).isEmpty());
    }

    @Test
    @DisplayName("a nameless proposal is dropped rather than becoming feature-unnamed.webm")
    void namelessProposalsAreIgnored() {
        Plan plan = new Plan();
        plan.getFeatures().add(new PlanFeature("", "shows something"));
        plan.getFeatures().add(new PlanFeature("Real one", "shows something"));

        assertEquals(List.of("Real one"), Feature.of(plan).stream().map(Feature::name).toList());
    }

    // ------------------------------------------------------------- approval

    @Test
    @DisplayName("only what was approved is owed")
    void onlyApprovedIsOwed() {
        List<Feature> proposed = Feature.of(planWith("Report export", "Monthly summary"));

        List<Feature> approved = Features.owed(proposed, List.of(
                ruling(proposed.get(0), Features.APPROVED),
                ruling(proposed.get(1), Features.DECLINED)));

        assertEquals(List.of("Report export"), approved.stream().map(Feature::name).toList());
    }

    @Test
    @DisplayName("silence is not consent")
    void unruledFeaturesAreNotApproved() {
        // The failure this prevents is the whole mechanism turning hostile: an agent that lists
        // nine features would otherwise saddle the project with nine recordings nobody asked for,
        // and the gate would park it until they all existed.
        List<Feature> proposed = Feature.of(planWith("Report export", "Monthly summary"));

        assertTrue(Features.owed(proposed, List.of()).isEmpty());
    }

    // ------------------------------------------------------------- recordings

    @Test
    @DisplayName("an approved feature with no clip is what is still owed")
    void unrecordedIsWhatIsOwed() throws IOException {
        List<Feature> proposed = Feature.of(planWith("Report export", "Monthly summary"));
        record(proposed.get(0), ".webm");

        List<RecordingTake> missing = Features.unrecorded(repo, proposed);

        assertEquals(List.of("Monthly summary"),
                missing.stream().map(take -> take.feature().name()).toList());
    }

    @Test
    @DisplayName("all three extensions are looked for, so the rule is not about the codec")
    void everyExtensionIsLookedFor() throws IOException {
        for (String extension : Feature.FORMATS) {
            Feature feature = new Feature("Format " + extension, "");
            record(feature, extension);

            assertTrue(Features.recordingOf(repo, feature).isPresent(), extension);
        }
    }

    @Test
    @DisplayName("a file that is not a recording does not count as one")
    void unusableFilesDoNotCount() throws IOException {
        // A zero-byte .webm is what a failed encode leaves behind, and it plays as a broken box in
        // the report. Counting it would turn a failure into a pass in the one place nobody looks.
        Feature feature = new Feature("Report export", "");
        Path file = repo.resolve(feature.recordingPath(".webm"));
        Files.createDirectories(file.getParent());
        Files.createFile(file);

        assertTrue(Features.recordingOf(repo, feature).isEmpty());
        assertTrue(Features.takeOf(repo, feature).problem().contains("not a video file"),
                "the gate has to say why, or the next turn writes the same file again");
    }

    @Test
    @DisplayName("an approval outlives the proposal, so the agent cannot cancel it by omission")
    void approvalsSurviveTheAgentDroppingThem() {
        // The opposite rule to review verdicts, deliberately. A verdict is a judgement about
        // something the agent claimed, so it expires with the claim; an approval is a request the
        // reader made, and a list the agent controls must not be able to cancel it.
        List<Feature> proposed = Feature.of(planWith("Report export"));
        Decision approved = ruling(proposed.get(0), Features.APPROVED);

        List<Feature> owed = Features.owed(List.of(), List.of(approved));

        assertEquals(List.of("Report export"), owed.stream().map(Feature::name).toList());
    }

    @Test
    @DisplayName("a feature you asked for yourself is owed, and takes its brief from your note")
    void featuresYouAskForAreOwed() {
        // Nothing proposed it, so the note left when it was asked for is the only description of
        // it that exists — and it is what the gate will tell the next session to film.
        List<Decision> asked = Decisions.merge(List.of(), List.of(Decision.of(
                "feature-offline-mode", "Offline mode", Features.APPROVED,
                "pull the network and show it still saves")));

        List<Feature> owed = Features.owed(List.of(), asked);

        assertEquals(1, owed.size());
        assertEquals("Offline mode", owed.get(0).name());
        assertEquals("pull the network and show it still saves", owed.get(0).demo());
        assertEquals("artifacts/feature-offline-mode.webm", owed.get(0).recordingPath(".webm"));
    }

    @Test
    @DisplayName("the page lists what was proposed and what was asked for, without duplicates")
    void listedCoversBothSources() {
        List<Feature> proposed = Feature.of(planWith("Report export"));
        List<Decision> rulings = List.of(
                ruling(proposed.get(0), Features.APPROVED),
                Decision.of("feature-offline-mode", "Offline mode", Features.APPROVED, ""));

        assertEquals(List.of("Report export", "Offline mode"),
                Features.listed(proposed, rulings).stream().map(Feature::name).toList());
    }

    @Test
    @DisplayName("a recording cannot be satisfied from outside the repository")
    void recordingsCannotEscapeTheRepository() {
        assertTrue(Features.recordingOf(repo, new Feature("../../../etc/passwd", "")).isEmpty());
    }
}
