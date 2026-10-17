package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** One feature's ruling, made from the dashboard: still wanted, not worth a clip, or the same as another. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureRequest {
    private String id = "";
    private String itemId = "";
    /** {@code approved} or {@code declined}. */
    private String verdict = "";
    /** The filmed feature this one is the same as, or blank. */
    private String sameAs = "";
    /** What the person wants changed about it, or blank. */
    private String note = "";
}
