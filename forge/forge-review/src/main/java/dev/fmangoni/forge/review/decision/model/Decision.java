package dev.fmangoni.forge.review.decision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the reader said about one {@link ReviewItem}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Decision {

    /** Matches {@link ReviewItem#id()}. */
    private String itemId;

    /** The item's text as it was when the decision was made, so the record stays readable. */
    private String text;

    /** {@code ok} to let it stand, {@code changes} to send it back. */
    private String verdict = "ok";

    /**
     * The note being added by this submission, if any.
     *
     * <p>Write-only, in effect: the form sends what was just typed and {@link Decisions#merge}
     * moves it into {@link #comments}. It is still read when loading a file written before
     * comments were kept, so an existing note is carried over rather than lost.
     */
    private String comment = "";

    /**
     * Every note left on this item, oldest first.
     *
     * <p>Kept whatever the verdict. A note on something you are content with is not nothing — "fine,
     * but it should be written down somewhere" is exactly the kind of remark that used to be typed,
     * discarded because the radio button said <em>Fine as it is</em>, and then typed again a week
     * later.
     */
    private List<Comment> comments = new ArrayList<>();

    /**
     * One submitted ruling, built by name.
     *
     * <p>The generated all-args constructor is positional, and adding {@link #comments} changed
     * its arity — which broke every caller at compile time, and would have silently transposed two
     * same-typed arguments if it had not. Callers use this instead.
     */
    public static Decision of(String itemId, String text, String verdict, String comment) {
        Decision decision = new Decision();
        decision.setItemId(itemId);
        decision.setText(text);
        decision.setVerdict(verdict);
        decision.setComment(comment);
        return decision;
    }

    public boolean wantsChanges() {
        return "changes".equalsIgnoreCase(verdict);
    }

    /** Whether anything has ever been said about this item. */
    public boolean hasComments() {
        return comments != null && !comments.isEmpty();
    }

    /**
     * When these notes were last handed to a session, or null if they never have been.
     *
     * <p>A note is only worth acting on once. Without this, one written beside a feature would
     * either be dropped — what happened to 003's "offer an ad to buy a car from a dealer", saved
     * under <em>Worth seeing</em> and never read by an agent — or, delivered, go back with every
     * later session as though it were new.
     */
    private Instant heardAt;

    /** Whether something has been said here that no session has been given yet. */
    public boolean hasUnheardComments() {
        return hasComments() && comments.stream().anyMatch(comment -> heardAt == null
                || (comment.at() != null && comment.at().isAfter(heardAt)));
    }

    /** The most recent note, or blank if none. */
    public String latestComment() {
        return hasComments() ? comments.get(comments.size() - 1).text() : "";
    }
}
