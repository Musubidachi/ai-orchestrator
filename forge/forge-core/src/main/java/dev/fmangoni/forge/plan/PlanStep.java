package dev.fmangoni.forge.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One unit of remaining work, as the agent described it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanStep {

    /** Short imperative description, e.g. "Parse the record definitions into a field tree". */
    private String step = "";

    /** How to do it: the approach, the files, the decisions already made. */
    private String detail = "";

    /** The agent's own estimate. Treated as a hint for ordering, never as a promise. */
    private int estimateMinutes;
}
