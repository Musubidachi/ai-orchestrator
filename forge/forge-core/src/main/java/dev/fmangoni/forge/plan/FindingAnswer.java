package dev.fmangoni.forge.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the agent says it did about one blocking review finding, by the finding's id.
 *
 * <p>Handed to the next reviewer beside the finding, so "fixed" is checked against a specific
 * claim — which file, which test — rather than against the reviewer's memory of a defect it has
 * never seen before.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FindingAnswer {

    private String id = "";

    /** {@code fixed}, or {@code disputed} with a test that shows the behaviour is already right. */
    private String action = "";

    private String how = "";

    /** The test that fails without the fix, or that demonstrates the dispute. */
    private String test = "";

    /** One line for the reviewer: what was done, and where the proof is. */
    public String said() {
        StringBuilder out = new StringBuilder(action.isBlank() ? "(no action given)" : action);
        if (!how.isBlank()) {
            out.append(" — ").append(how);
        }
        if (!test.isBlank()) {
            out.append(" (test: ").append(test).append(')');
        }
        return out.toString();
    }
}
