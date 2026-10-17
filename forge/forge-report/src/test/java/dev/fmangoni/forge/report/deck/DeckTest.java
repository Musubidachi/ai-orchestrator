package dev.fmangoni.forge.report.deck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.plan.PlanStep;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a review is dealt for the quick review page.
 *
 * <p>The page is a second way of doing something the report's form already does, so most of what
 * is pinned here is that it is the <em>same</em> thing: the same ids, the same items, and the same
 * rule about what may not be waved through. A quicker page that ruled on something slightly
 * different would be worse than no page.
 */
class DeckTest {

    private static final String HALF_WRITTEN = "a file saved while the disk is full may be left half written";
    private static final String NEVER_OPENED = "the exported file has never been opened in the real application";

    @TempDir
    Path runs;

    private RunPaths paths;
    private RunState state;
    private Review review;

    @BeforeEach
    void setUp() throws IOException {
        paths = RunPaths.under(runs, "001");
        Files.createDirectories(paths.repo());

        Plan plan = new Plan();
        plan.setNotVerified(new ArrayList<>(List.of(HALF_WRITTEN, NEVER_OPENED)));
        PlanStep docs = new PlanStep();
        docs.setStep("write the user guide");
        plan.setRemaining(new ArrayList<>(List.of(docs)));
        plan.setFeatures(new ArrayList<>(List.of(
                new PlanFeature("Garage tuning", "change the gearing, watch the curve move"),
                new PlanFeature("Pink slip wager", "win a race, take the other car"))));

        state = new RunState();
        state.setId("001");
        state.setTitle("Drag Racing");
        state.setPhase(Phase.DONE);
        state.setPlan(plan);

        review = new Review();
        review.setReviewer("CODEX (gpt-6-astra)");
        review.setFindings(new ArrayList<>(List.of(rounding(), note())));
    }

    private static Finding rounding() {
        Finding finding = new Finding();
        finding.setSeverity("blocking");
        finding.setFile("src/Price.java");
        finding.setSummary("Intermediate division rounding violates the store-only truncation contract");
        finding.setDetail("divide() rounds HALF_UP before the value is stored.");
        finding.setPlain("A price ending in half a cent is rounded up instead of down");
        finding.setStakes("Wrong_Result");
        return finding;
    }

    private static Finding note() {
        Finding finding = new Finding();
        finding.setSeverity("note");
        finding.setFile("src/Ui.java");
        finding.setSummary("a long method");
        return finding;
    }

    private DeckView deal() {
        return Deck.of(runs, paths, state, review);
    }

    private static String idOfClaim(String claim) {
        return ReviewItem.of("unproven", ReviewItem.NOT_VERIFIED, claim).id();
    }

    @Test
    @DisplayName("a blocking finding is answered on its own, and leads with what a user would notice")
    void blockingFindingsAreDealtFirstInPlainWords() {
        DeckView deck = deal();

        assertEquals(1, deck.getMustDecide().size());
        Card card = deck.getMustDecide().getFirst();
        assertEquals(ReviewItem.idOf(rounding()), card.getId(), "the id the form and the reviewer both use");
        assertEquals("blocking", card.getKind());
        assertEquals("A price ending in half a cent is rounded up instead of down", card.getHeadline());
        assertEquals("wrong result", card.getStakes(), "the reviewer's word, tidied");
        assertEquals("src/Price.java — Intermediate division rounding violates the store-only truncation contract",
                card.getText(), "what the ruling is filed under and what the agent is quoted");
        assertTrue(card.getDetail().contains("store-only truncation contract")
                && card.getDetail().contains("rounds HALF_UP"), "the technical wording is a click away, not gone");
    }

    @Test
    @DisplayName("a finding written before reviewers were asked for a plain line shows its summary, and no tag")
    void olderFindingsFallBackToTheSummary() {
        Finding old = rounding();
        old.setPlain(null);
        old.setStakes("catastrophic");
        review.setFindings(new ArrayList<>(List.of(old)));

        Card card = deal().getMustDecide().getFirst();

        assertEquals("Intermediate division rounding violates the store-only truncation contract", card.getHeadline());
        assertEquals("", card.getStakes(), "a word that is not one of the five is not shown as though it were");
        assertTrue(card.getDetail().startsWith("src/Price.java"));
    }

    @Test
    @DisplayName("the attempts already spent on a finding, and why the last fell short, come from the run state")
    void carriesTheLoopsRecordOfAFinding() {
        Finding carried = rounding();
        carried.setId(ReviewItem.idOf(carried));
        carried.setAttempts(2);
        carried.setStillPresent("the test added only covers whole cents");
        state.setBlockingFindings(new ArrayList<>(List.of(carried)));

        Card card = deal().getMustDecide().getFirst();

        assertEquals(2, card.getAttempts());
        assertEquals("the test added only covers whole cents", card.getStillPresent());
    }

    @Test
    @DisplayName("what the gate does not prove, and what remains, can be skimmed together")
    void theRestIsSkimmed() {
        DeckView deck = deal();

        assertEquals(List.of(HALF_WRITTEN, NEVER_OPENED, "write the user guide"),
                deck.getSkim().stream().map(Card::getHeadline).toList());
        assertEquals(List.of("unproven", "unproven", "remaining"), deck.getSkim().stream().map(Card::getKind).toList());
    }

    @Test
    @DisplayName("something ruled on before that has come back is a new question, answered on its own")
    void whatCameBackCannotBeWavedThrough() {
        ReviewHistory.append(paths.reviewHistoryFile(), new Sitting(Instant.parse("2026-09-27T20:00:00Z"),
                "accepted", List.of(Decision.of(idOfClaim(NEVER_OPENED), NEVER_OPENED, "ok", "fine for now")),
                List.of()));

        DeckView deck = deal();

        assertEquals(List.of(ReviewItem.idOf(rounding()), idOfClaim(NEVER_OPENED)),
                deck.getMustDecide().stream().map(Card::getId).toList());
        assertTrue(deck.getMustDecide().getLast().getCameBack().startsWith("Came back — you ruled it"),
                deck.getMustDecide().getLast().getCameBack());
        assertEquals(2, deck.getSkim().size(), "and it is no longer in the pile that starts as fine");
    }

    @Test
    @DisplayName("notes already written about an item are on its card")
    void earlierNotesTravelWithTheCard() {
        DecisionStore.save(paths.decisionsFile(), Decisions.merge(List.of(), List.of(
                Decision.of(idOfClaim(HALF_WRITTEN), HALF_WRITTEN, "ok", "only matters on a laptop"))));

        // Ruled on before, so it has come back and is answered on its own.
        Card card = deal().getMustDecide().stream()
                .filter(c -> c.getId().equals(idOfClaim(HALF_WRITTEN))).findFirst().orElseThrow();

        assertEquals(List.of("only matters on a laptop"), card.getNotes());
    }

    @Test
    @DisplayName("only features nobody has ruled on are asked about, each with the clip to judge it by")
    void unansweredFeaturesComeWithTheirClips() throws IOException {
        DecisionStore.save(paths.featuresFile(), List.of(
                Decision.of("feature-garage-tuning", "Garage tuning", "approved", "")));
        Path clip = paths.repo().resolve("artifacts/feature-pink-slip-wager.gif");
        Files.createDirectories(clip.getParent());
        Clips.write(clip);

        DeckView deck = deal();

        assertEquals(List.of("Pink slip wager"), deck.getFeatures().stream().map(FeatureCard::getName).toList());
        assertEquals(1, deck.getFeaturesAlreadyRuled());
        FeatureCard card = deck.getFeatures().getFirst();
        assertEquals("/runs/001/repo/artifacts/feature-pink-slip-wager.gif", card.getClip(),
                "an address the editor serves, not a path on this machine");
        assertEquals("image", card.getClipKind());
        assertEquals("Recorded by the last gate run.", card.getClipNote());
    }

    @Test
    @DisplayName("a feature with nothing to watch says so, and says what keeping it costs")
    void aFeatureWithNoClip() {
        FeatureCard card = deal().getFeatures().getFirst();

        assertEquals("", card.getClip());
        assertEquals("", card.getClipKind());
        assertTrue(card.getClipNote().contains("takes a session"), card.getClipNote());
    }

    @Test
    @DisplayName("ideas the agent has not built are counted, not dealt")
    void ideasAreOnlyCounted() {
        state.getPlan().getIdeas().add(new PlanFeature("Rival leaderboard", "race the same ghost twice"));

        DeckView deck = deal();

        assertEquals(1, deck.getIdeasWaiting());
        assertEquals(2, deck.getFeatures().size(), "an idea is not something the project does");
    }

    @Test
    @DisplayName("the reason a submission cannot be acted on is the form's reason, word for word")
    void theNoticeIsTheFormsNotice() {
        assertEquals("", deal().getNotice(), "reviewed and finished: nothing stands in the way");

        DeckView unreviewed = Deck.of(runs, paths, state, null);

        assertTrue(unreviewed.getNotice().contains("cannot be accepted yet"), unreviewed.getNotice());
        assertEquals(List.of(), unreviewed.getMustDecide(), "and with no review there is no finding to deal");
    }

    @Test
    @DisplayName("two security findings with the same title each show their own location")
    void securityFindingsAreMatchedByTheWholeLine() {
        new SecurityReport("0123456789abcdef", "tree", List.of(
                LayerResult.ran("secrets", "12 file(s)", List.of(
                        new SecurityFinding("secrets", "critical", "AWS access key id committed", "src/keys.ts:3",
                                "AKIA… (20 characters, redacted)", true),
                        new SecurityFinding("secrets", "critical", "AWS access key id committed", "deploy/env.sh:9",
                                "AKIA… (20 characters, redacted)", true)))))
                .save(paths.securityFile());

        List<Card> security = deal().getMustDecide().stream().filter(card -> card.getKind().equals("security")).toList();

        assertEquals(2, security.size());
        assertTrue(security.get(0).getDetail().contains("src/keys.ts:3"), security.get(0).getDetail());
        assertTrue(security.get(1).getDetail().contains("deploy/env.sh:9"),
                "the second card showed the first one's location:\n" + security.get(1).getDetail());
    }

    @Test
    @DisplayName("a project with nothing outstanding deals no cards at all")
    void nothingOutstanding() {
        state.setPlan(new Plan());
        review.setFindings(new ArrayList<>());

        DeckView deck = deal();

        assertEquals(List.of(), deck.getMustDecide());
        assertEquals(List.of(), deck.getSkim());
        assertEquals(List.of(), deck.getFeatures());
        assertEquals("/runs/001/report.html", deck.getReport());
    }
}
