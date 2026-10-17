package dev.fmangoni.forge.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The cross-review's findings, as returned by the model that did not build the project. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Review {

    private List<Finding> findings = new ArrayList<>();

    /**
     * What the reviewer said about each blocking finding from the round before, by id.
     *
     * <p>Empty on a first review. The pipeline turns these into the findings that carry forward:
     * one the reviewer does not settle stays blocking, with its attempts counted.
     */
    private List<Recheck> rechecks = new ArrayList<>();

    /** Set when the reviewer's reply could not be parsed, so the report can say so honestly. */
    private String parseError;

    /**
     * For a polish built to a picked design: whether the reviewer opened the picture and the result
     * and compared them, in its own words. Null when it did not say.
     *
     * <p>Asked for because it was skipped without a word. 003's first review against its design
     * filed "the design HTML and result screenshot were not inspected" as a note, and the project
     * went to DONE as though it had been compared.
     */
    @JsonProperty("compared_with_design")
    private Boolean comparedWithDesign;

    /**
     * True when the reviewer never ran — out of quota, or not logged in.
     *
     * <p>Distinct from a reviewer that ran and said nothing. An empty findings list means "looked
     * and found nothing"; this means "did not look", and treating the two alike accepts a project
     * that was never reviewed.
     */
    private boolean unavailable;

    /** When the reviewing vendor said its window reopens, if it said. */
    private Instant retryAfter;

    /**
     * Which vendor read it, and with which model.
     *
     * <p>Recorded because it was not, and the question "is Antigravity doing any reviews" could
     * only be answered by matching file timestamps in the logs directory. It turned out to have
     * done every one of the six on this machine — and on one of them it had been handed a diff that
     * was 93% an encrypted PDF, which is worth knowing before blaming a free model for the review
     * that came back.
     */
    private String reviewer;

    /** Bytes of diff the reviewer was actually shown, and how much was held back from it. */
    private String scope;

    /**
     * What the cheap per-feature pass made of each claim, before the review itself ran.
     *
     * <p>Kept even when the review that followed is clean: "nobody checked whether the features are
     * real" and "each one was checked and holds" are different states, and a report that shows
     * neither cannot tell them apart. Absent from reviews written before the pass existed.
     */
    private List<FeatureCheck> featureChecks = new ArrayList<>();

    /** Claims the cheap pass could not find in the code; the leads the big review is handed. */
    public List<FeatureCheck> unprovenFeatures() {
        return featureChecks == null ? List.of() : featureChecks.stream().filter(FeatureCheck::unproven).toList();
    }

    public List<Finding> blocking() {
        return findings.stream().filter(Finding::blocking).toList();
    }

    public List<Finding> notes() {
        return findings.stream().filter(f -> !f.blocking()).toList();
    }

    public boolean clean() {
        return blocking().isEmpty();
    }

    public static Review failed(String reason) {
        Review review = new Review();
        review.setParseError(reason);
        return review;
    }

    /** The reviewer could not be reached; the project is not reviewed and must not be accepted. */
    public static Review unavailable(String reason, Instant retryAfter) {
        Review review = failed(reason);
        review.setUnavailable(true);
        review.setRetryAfter(retryAfter);
        return review;
    }
}
