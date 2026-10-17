package dev.fmangoni.forge.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One observation from the cross-review.
 *
 * <p>Severity is treated permissively: anything that is not recognisably "blocking" counts as a
 * note. A reviewer inventing its own severity word should not be able to halt a project, and an
 * unparsed severity silently blocking a run would be very hard to diagnose at 3am.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Finding {

    private String severity;
    private String file;
    private String summary;
    private String detail;

    /**
     * What somebody using the program would notice, in words they would use.
     *
     * <p>The summary is written for whoever will fix it: "intermediate division rounding violates
     * the store-only truncation contract". The person who has to rule on it did not write the code,
     * and that sentence gives them nothing to decide with. This one is for them. Absent from
     * reviews written before it was asked for, where the summary stands in.
     */
    private String plain;

    /**
     * How much it matters, as one of a few plain words: {@code cosmetic}, {@code wrong result},
     * {@code data loss}, {@code security} or {@code upkeep} — the last for something only a
     * maintainer would ever meet. Blank when the reviewer did not say.
     */
    private String stakes;

    /**
     * Stable across review rounds, so the next reviewer can say whether this one was fixed.
     *
     * <p>Assigned by the pipeline, never by the reviewer, and derived from the file and summary as
     * first raised — the same text the review form keys a ruling on, so a finding and what a person
     * said about it share one name. A reviewer rewords the same defect every round; without an id
     * to answer to, "still broken" arrived as a new finding each time and nothing noticed that the
     * thing sent back had come back.
     */
    private String id;

    /** Times this finding has been handed to the building agent to fix. */
    private int attempts;

    /**
     * The reviewer's reason for saying this is still present after the last attempt, if it did.
     *
     * <p>The most useful sentence the next repair turn can be given: not the original complaint
     * again, but why the last fix did not answer it.
     */
    private String stillPresent;

    public boolean blocking() {
        return severity != null && severity.trim().equalsIgnoreCase("blocking");
    }
}
