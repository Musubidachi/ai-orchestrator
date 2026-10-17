package dev.fmangoni.forge.review.decision;

import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Folds a submitted review into what was already on record.
 *
 * <p>A submission is not the whole truth about a project — it is one sitting. The verdicts it
 * carries replace the previous ones, because a verdict is a current position; the notes are added
 * to, because a note is something that was said and stays said.
 *
 * <p>Pure on purpose. Deciding what to keep is the part worth testing, and it should not require a
 * run directory, a phase, or a quota window to exercise.
 */
@UtilityClass
public class Decisions {

    /**
     * @param previous what is on disk, possibly written before comments were kept
     * @param incoming what was just submitted; its {@code comment} is the note being added now
     * @return one decision per item, verdicts from {@code incoming}, notes accumulated
     */
    public List<Decision> merge(List<Decision> previous, List<Decision> incoming) {
        Map<String, Decision> byId = new LinkedHashMap<>();
        for (Decision decision : previous == null ? List.<Decision>of() : previous) {
            byId.put(decision.getItemId(), migrate(decision));
        }

        for (Decision submitted : incoming == null ? List.<Decision>of() : incoming) {
            Decision current = byId.get(submitted.getItemId());
            if (current == null) {
                current = new Decision();
                current.setItemId(submitted.getItemId());
                byId.put(submitted.getItemId(), current);
            }
            current.setText(submitted.getText());
            current.setVerdict(submitted.getVerdict());
            append(current, submitted.getComment());
            // Cleared so the stored record never carries a pending note as well as a kept one;
            // the form's input is for what you are adding, not for what you have already said.
            current.setComment("");
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Re-keys decisions written when items were numbered by position.
     *
     * <p>Matched on the text that was stored alongside the ruling, which is the only thing that
     * survived the change in identity. A decision whose text still matches an item on the current
     * list is that item's, whatever it used to be called; anything else is left alone and turns up
     * as an orphan rather than being attached to whichever claim now happens to sit at its index.
     */
    public List<Decision> realign(List<ReviewItem> items, List<Decision> stored) {
        Map<String, String> idByText = new LinkedHashMap<>();
        for (ReviewItem item : items) {
            idByText.put(normalise(item.text()), item.id());
        }

        List<Decision> out = new ArrayList<>();
        for (Decision decision : stored == null ? List.<Decision>of() : stored) {
            String current = idByText.get(normalise(decision.getText()));
            if (current != null && !current.equals(decision.getItemId())) {
                decision.setItemId(current);
            }
            out.add(decision);
        }
        return out;
    }

    /**
     * Notes left on items that are no longer on the list, so they are not silently dropped.
     *
     * <p>The agent rewrites what it has not proven at the end of every session, and the list
     * shrinks as often as it grows. Four notes on this machine had already fallen off that way —
     * "nothing should freeze the sim" among them — and nothing anywhere showed them. Whether the
     * claim they answered is still listed is the agent's business; whether the remark is kept is
     * not.
     */
    public List<Decision> orphans(List<ReviewItem> items, List<Decision> stored) {
        Set<String> live = ids(items);
        List<Decision> out = new ArrayList<>();
        for (Decision decision : stored == null ? List.<Decision>of() : stored) {
            if (!live.contains(decision.getItemId()) && migrate(decision).hasComments()) {
                out.add(decision);
            }
        }
        return out;
    }

    /**
     * The rulings that still decide anything.
     *
     * <p>Near enough the complement of {@link #orphans}, and the reason the two are separate: a
     * note is kept for good, but a verdict is a position on one specific statement, and the
     * statement can stop being made. A "needs work" left on a claim that has since disappeared is
     * shown read-only on the report, with no control to withdraw it — so counting it would block
     * acceptance permanently and put sentences nobody can edit into the next session's brief. One
     * project on this machine was already in exactly that state, with four of them.
     *
     * <p>What was ticked in this sitting counts whatever the list says, which is the "near enough".
     * The form renders one row per current item, so a submitted id that is not among them means
     * the list moved between opening the page and pressing the button — and the judgement of the
     * person who just read the thing outranks a list Forge re-derived a moment later.
     *
     * @param submitted this sitting's form, or empty when asking about the record alone
     */
    public List<Decision> current(List<ReviewItem> items, List<Decision> submitted,
                                  List<Decision> stored) {
        Set<String> live = ids(items);
        for (Decision decision : submitted == null ? List.<Decision>of() : submitted) {
            live.add(decision.getItemId());
        }
        List<Decision> out = new ArrayList<>();
        for (Decision decision : stored == null ? List.<Decision>of() : stored) {
            if (live.contains(decision.getItemId())) {
                out.add(decision);
            }
        }
        return out;
    }

    private Set<String> ids(List<ReviewItem> items) {
        Set<String> live = new HashSet<>();
        for (ReviewItem item : items == null ? List.<ReviewItem>of() : items) {
            live.add(item.id());
        }
        return live;
    }

    private String normalise(String text) {
        return text == null ? "" : text.strip();
    }

    /**
     * Adds a note, unless there is nothing to add.
     *
     * <p>Blank is ignored, and so is a repeat of the note already at the end. Submitting the form
     * twice — which happens, because the page reloads and the buttons are still there — should not
     * leave the same sentence on the record twice.
     */
    private void append(Decision decision, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        if (decision.getComments() == null) {
            decision.setComments(new ArrayList<>());
        }
        if (note.strip().equals(decision.latestComment())) {
            return;
        }
        decision.getComments().add(Comment.now(note));
    }

    /**
     * Carries a file written before comments existed into the new shape.
     *
     * <p>Without this, the first submission after the upgrade would silently drop whatever was
     * typed under the old single-field arrangement — which is the exact failure this whole change
     * is about, reintroduced by the change itself.
     */
    private Decision migrate(Decision decision) {
        if (decision.getComments() == null) {
            decision.setComments(new ArrayList<>());
        }
        if (decision.getComments().isEmpty()
                && decision.getComment() != null && !decision.getComment().isBlank()) {
            decision.getComments().add(new Comment(decision.getComment().strip(), null));
        }
        decision.setComment("");
        return decision;
    }
}
