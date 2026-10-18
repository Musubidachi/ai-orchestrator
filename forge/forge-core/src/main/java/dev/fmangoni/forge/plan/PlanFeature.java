package dev.fmangoni.forge.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One capability the agent says the project has, put forward for a recorded demonstration.
 *
 * <p>A proposal and nothing more. What the project owes a recording of is what the person it was
 * built for approves from this list — see {@code Feature}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanFeature {

    /** A few words: "Report export", "Bulk import". Becomes the recording's filename. */
    private String name = "";

    /**
     * What a recording of it should show, in one sentence.
     *
     * <p>Written by the agent because it is the only party that knows which three clicks make the
     * feature visible, and read by the next session, which has to film exactly that.
     */
    private String demo = "";
}
