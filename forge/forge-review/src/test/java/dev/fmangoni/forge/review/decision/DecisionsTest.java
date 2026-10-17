package dev.fmangoni.forge.review.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Folding one sitting's review into what was already on record.
 *
 * <p>The property that matters is that nothing a person wrote is ever lost. Verdicts are a current
 * position and are replaced; notes are things that were said and accumulate. Everything here is
 * about the second half, because that is the half that used to be thrown away — a remark typed
 * beside <em>Fine as it is</em> went nowhere, and a remark typed while the cross-review was
 * rate-limited went nowhere at all.
 */
class DecisionsTest {

    private Decision submitted(String id, String verdict, String comment) {
        Decision decision = new Decision();
        decision.setItemId(id);
        decision.setText("the draw branch has never been rendered");
        decision.setVerdict(verdict);
        decision.setComment(comment);
        return decision;
    }

    private Decision only(List<Decision> decisions) {
        assertEquals(1, decisions.size(), decisions.toString());
        return decisions.get(0);
    }

    @Test
    @DisplayName("a note on an item left standing is kept")
    void keepsNotesOnStandingItems() {
        // The reported bug, in one assertion: "Fine as it is" plus a remark used to store the
        // verdict and discard the remark.
        List<Decision> merged = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "fine, but write it down")));

        assertEquals(List.of("fine, but write it down"),
                only(merged).getComments().stream().map(Comment::text).toList());
    }

    @Test
    @DisplayName("notes accumulate across sittings rather than replacing each other")
    void notesAccumulate() {
        List<Decision> first = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "looks fine to me")));
        List<Decision> second = Decisions.merge(
                first, List.of(submitted("unproven-0", "changes", "actually, no — it flickers")));

        assertEquals(List.of("looks fine to me", "actually, no — it flickers"),
                only(second).getComments().stream().map(Comment::text).toList());
    }

    @Test
    @DisplayName("the verdict is replaced, because it is a position rather than a remark")
    void verdictIsReplaced() {
        List<Decision> first = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "changes", "not good enough")));
        List<Decision> second = Decisions.merge(
                first, List.of(submitted("unproven-0", "ok", "")));

        assertEquals("ok", only(second).getVerdict());
        assertEquals(1, only(second).getComments().size(), "the earlier remark still stands");
    }

    @Test
    @DisplayName("submitting the same note twice does not record it twice")
    void repeatsAreIgnored() {
        // The page reloads after a submission and the button is still there, so this happens.
        List<Decision> first = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "same words")));
        List<Decision> second = Decisions.merge(
                first, List.of(submitted("unproven-0", "ok", "same words")));

        assertEquals(1, only(second).getComments().size());
    }

    @Test
    @DisplayName("an empty box adds nothing")
    void blankAddsNothing() {
        List<Decision> merged = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "   ")));

        assertTrue(only(merged).getComments().isEmpty());
    }

    @Test
    @DisplayName("a note written before notes were dated is still readable")
    void undatedNotesSurvive() {
        // Comment.at is null for a note migrated from the old single-field arrangement. The form
        // renders the date only when there is one, so this must not be turned into a fake date.
        Decision old = new Decision();
        old.setItemId("unproven-0");
        old.setComment("said before the upgrade");

        Decision merged = only(Decisions.merge(List.of(old), List.of()));

        assertEquals(List.of("said before the upgrade"),
                merged.getComments().stream().map(Comment::text).toList());
    }

    @Test
    @DisplayName("a file written before comments existed does not lose its note on the next submit")
    void legacyNotesSurviveTheNextSubmission() {
        // The failure this guards against is the change itself reintroducing the bug it fixes.
        Decision old = new Decision();
        old.setItemId("unproven-0");
        old.setComment("the original remark");

        List<Decision> merged = Decisions.merge(
                List.of(old), List.of(submitted("unproven-0", "changes", "and another thing")));

        assertEquals(List.of("the original remark", "and another thing"),
                only(merged).getComments().stream().map(Comment::text).toList());
    }

    @Test
    @DisplayName("a note is dated when it is made")
    void notesAreDated() {
        Instant before = Instant.now().minusSeconds(1);

        Decision merged = only(Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "a remark"))));

        Instant at = merged.getComments().get(0).at();
        assertNotNull(at);
        assertTrue(at.isAfter(before), "expected a timestamp from this run, got " + at);
    }

    @Test
    @DisplayName("the pending note is cleared once it has been recorded")
    void pendingNoteIsNotKeptTwice() {
        // Otherwise the stored record carries the same sentence as both a kept comment and an
        // unsubmitted one, and the next merge would append it again.
        Decision merged = only(Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "ok", "a remark"))));

        assertEquals("", merged.getComment());
    }

    // ------------------------------------------------------- identity across sessions

    private ReviewItem item(String text) {
        return ReviewItem.of("unproven", ReviewItem.NOT_VERIFIED, text);
    }

    @Test
    @DisplayName("the same claim keeps its id when the agent rewrites the list")
    void identityFollowsTheClaim() {
        // Positions move as the list is regenerated; the sentence does not.
        assertEquals(item("the draw branch has never been rendered").id(),
                item("the draw branch has never been rendered").id());
        assertNotEquals(item("the draw branch has never been rendered").id(),
                item("the RPM gauge has never been rendered").id());
    }

    @Test
    @DisplayName("a note filed under an old positional id follows its claim")
    void positionalNotesAreRealigned() {
        // Measured on a real project here: nine sessions in, a note reading "this should be
        // addressed in a later build" was filed against unproven-2 when that meant a
        // floating-point tolerance, and unproven-2 had since come to mean an RPM gauge.
        Decision old = new Decision();
        old.setItemId("unproven-2");
        old.setText("agreement is asserted to 1e-6, not exact");
        old.setComment("address this in a later build");

        ReviewItem moved = item("agreement is asserted to 1e-6, not exact");
        Decision realigned = only(Decisions.realign(List.of(item("something else"), moved),
                new ArrayList<>(List.of(old))).stream()
                .filter(d -> d.getItemId().equals(moved.id())).toList());

        assertEquals("address this in a later build", realigned.getComment());
    }

    @Test
    @DisplayName("a claim that was reworded does not inherit the old approval")
    void rewordedClaimsStartClean() {
        // The dangerous direction. "Fine as it is" was said about a specific sentence, and
        // carrying it onto a different one records a judgement nobody made.
        Decision old = new Decision();
        old.setItemId("unproven-0");
        old.setText("the draw branch has never been rendered");
        old.setVerdict("ok");

        List<Decision> realigned = Decisions.realign(
                List.of(item("the draw branch is now rendered but not asserted")),
                new ArrayList<>(List.of(old)));

        assertEquals("unproven-0", realigned.get(0).getItemId(), "it must not be re-keyed");
    }

    @Test
    @DisplayName("a note whose claim has vanished is kept as an orphan, not dropped")
    void vanishedClaimsKeepTheirNotes() {
        Decision old = new Decision();
        old.setItemId("unproven-4");
        old.setText("the hidden-tab pause e2e fakes document.hidden");
        old.setComment("nothing should freeze the sim");

        List<Decision> orphans = Decisions.orphans(
                List.of(item("something entirely different")),
                new ArrayList<>(List.of(old)));

        assertEquals(1, orphans.size(), "a note vanished with the claim it answered");
        assertEquals("nothing should freeze the sim", orphans.get(0).latestComment());
    }

    @Test
    @DisplayName("an item still on the list is not an orphan")
    void livingItemsAreNotOrphans() {
        ReviewItem live = item("the draw branch has never been rendered");
        Decision decision = new Decision();
        decision.setItemId(live.id());
        decision.setText(live.text());
        decision.setComment("a remark");

        assertTrue(Decisions.orphans(List.of(live),
                new ArrayList<>(List.of(decision))).isEmpty());
    }

    @Test
    @DisplayName("a verdict on a claim the agent has dropped no longer counts for anything")
    void staleVerdictsAreNotCurrent() {
        // Found on this machine: one project carried four "needs work" rulings on claims the agent
        // had stopped making. The report shows those read-only, so there was no control anywhere to
        // withdraw them — they would have blocked acceptance for good and sent the agent back to
        // work on four sentences that no longer existed.
        ReviewItem live = item("the draw branch has never been rendered");
        Decision onLive = new Decision();
        onLive.setItemId(live.id());
        onLive.setText(live.text());
        onLive.setVerdict("ok");

        Decision stale = new Decision();
        stale.setItemId("unproven-4");
        stale.setText("a claim the agent has since stopped making");
        stale.setVerdict("changes");

        List<Decision> current = Decisions.current(List.of(live), List.of(),
                new ArrayList<>(List.of(onLive, stale)));

        assertEquals(1, current.size(), "a dead claim still had a vote");
        assertEquals(live.id(), current.get(0).getItemId());
    }

    @Test
    @DisplayName("current and orphaned are complements, so nothing falls between them")
    void currentAndOrphansPartitionTheRecord() {
        ReviewItem live = item("the draw branch has never been rendered");
        Decision onLive = new Decision();
        onLive.setItemId(live.id());
        onLive.setText(live.text());
        onLive.setComment("a remark");

        Decision stale = new Decision();
        stale.setItemId("unproven-4");
        stale.setText("a claim the agent has since stopped making");
        stale.setComment("worth remembering anyway");

        List<Decision> stored = Decisions.merge(
                List.of(), new ArrayList<>(List.of(onLive, stale)));

        assertEquals(1, Decisions.current(List.of(live), List.of(), stored).size());
        assertEquals(1, Decisions.orphans(List.of(live), stored).size());
    }

    @Test
    @DisplayName("an item nobody has touched this time keeps everything it had")
    void untouchedItemsSurvive() {
        List<Decision> first = Decisions.merge(
                List.of(), List.of(submitted("unproven-0", "changes", "fix this")));

        List<Decision> second = Decisions.merge(
                first, List.of(submitted("unproven-1", "ok", "")));

        assertEquals(2, second.size());
        assertEquals("changes", second.get(0).getVerdict());
        assertEquals(1, second.get(0).getComments().size());
    }
}
