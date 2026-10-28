package dev.fmangoni.forge.review.feature;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the cheap pass made of one feature the agent says the project has.
 *
 * <p>Kept in {@code review.json} beside the findings, so the report can show which claims were
 * checked and by what — a claim nobody looked at and a claim that held up read identically
 * otherwise.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureCheck {

    /** {@link #HOLDS}, {@link #UNPROVEN} or {@link #UNCHECKED}. */
    public static final String HOLDS = "holds";

    /** The code does not appear to do what the feature claims, or nothing shows that it does. */
    public static final String UNPROVEN = "unproven";

    /** The pass never ran for this feature — out of quota, or an unreadable reply. */
    public static final String UNCHECKED = "unchecked";

    private String feature = "";

    private String verdict = UNCHECKED;

    /** One line: what the reviewer found, or why it could not look. */
    private String why = "";

    public boolean unproven() {
        return UNPROVEN.equals(verdict);
    }

    /** Whether a reviewer gave a verdict on it, either way; anything else is owed to the next one. */
    public boolean answered() {
        return HOLDS.equals(verdict) || unproven();
    }

    public static FeatureCheck unchecked(String feature, String why) {
        return new FeatureCheck(feature, UNCHECKED, why);
    }
}
