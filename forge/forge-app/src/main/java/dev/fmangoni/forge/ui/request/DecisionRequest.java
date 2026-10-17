package dev.fmangoni.forge.ui.request;

import dev.fmangoni.forge.review.decision.model.Decision;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A submitted review: every outstanding item, every feature, and what the reader said about each.
 *
 * <p>One request rather than two. Features had an endpoint of their own, and because approving one
 * reopens the project, submitting features first made the review that followed it refuse with
 * "still running" — so the two halves of one sitting could not both be recorded.
 */
@Data
public class DecisionRequest {

    private String id;

    private List<Decision> decisions = new ArrayList<>();

    /**
     * Rulings on the features, in the same shape and with a different vocabulary.
     *
     * <p>An entry with a blank {@code itemId} is one the reader asked for themselves; the server
     * derives the id from the name so the name-to-filename rule stays in one place.
     */
    private List<Decision> features = new ArrayList<>();
}
