package dev.fmangoni.forge.review.decision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One submitted review: what was ruled, when, and what came of it.
 *
 * <p>Kept as a history rather than folded into the current rulings. A ruling used to live on as the
 * pre-selected answer to whatever the item said next time, so a defect sent back, "fixed", and
 * raised again arrived already ticked with last week's opinion of it. Once submitted, a ruling is a
 * record of what was decided then; anything that comes back is a new question.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Sitting {

    private Instant at;

    /** What Forge did with it, in the words it answered the page with. */
    private String outcome = "";

    /** The review items ruled on, each with the note typed in this sitting. */
    private List<Decision> rulings = new ArrayList<>();

    /** The features ruled on in the same sitting. */
    private List<Decision> features = new ArrayList<>();
}
