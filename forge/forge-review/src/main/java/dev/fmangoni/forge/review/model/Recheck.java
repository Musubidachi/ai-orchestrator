package dev.fmangoni.forge.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The reviewer's verdict on one blocking finding from the round before: fixed or not.
 *
 * <p>Asked for by id, in the same reply as the review itself. A fresh open-ended review cannot tell
 * whether the defect the agent was sent back to fix is gone — it only produces new wording for
 * whatever it notices — so the finding that was sent back is checked by name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Recheck {

    private String id;

    /** {@code fixed}, {@code still_present} or {@code withdrawn}. */
    private String status;

    /** Why, in the reviewer's words. */
    private String note;

    /**
     * Whether the finding no longer stands, fixed or given up by the reviewer.
     *
     * <p>Anything else — {@code still_present}, a word the reviewer invented, nothing at all — keeps
     * it. A defect only stops being mandatory when the reviewer says so in terms.
     */
    public boolean settled() {
        String said = status == null ? "" : status.strip().toLowerCase();
        return said.equals("fixed") || said.equals("withdrawn");
    }

    /**
     * Whether it lets a finding go without saying why.
     *
     * <p>A finding cost the builder a turn; letting it go has to point at something, the line that
     * fixes it or the reason it was wrong. 003.3's last recheck ruled both of its findings fixed in
     * under three seconds with no note on either, and nothing anyone could check behind it.
     */
    public boolean unexplained() {
        return settled() && (note == null || note.isBlank());
    }
}
