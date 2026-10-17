package dev.fmangoni.forge.security.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * What one security layer did, and what it found.
 *
 * <p>The status is as important as the findings. An empty list from a layer that ran means it
 * looked and found nothing; an empty list from a layer whose tool is not installed means nobody
 * looked. The report shows the difference, because a clean-looking security section over checks
 * that never ran is exactly the false comfort this is meant to replace.
 *
 * @param status {@code ran}, {@code skipped}, {@code not-installed} or {@code failed}
 * @param detail why it was skipped or failed, what is missing, or what "partial" means
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LayerResult(String layer, String status, String detail,
                          List<SecurityFinding> findings) {

    public static final String RAN = "ran";
    public static final String SKIPPED = "skipped";
    public static final String NOT_INSTALLED = "not-installed";
    public static final String FAILED = "failed";

    public static LayerResult ran(String layer, String detail, List<SecurityFinding> findings) {
        return new LayerResult(layer, RAN, detail == null ? "" : detail, List.copyOf(findings));
    }

    public static LayerResult skipped(String layer, String why) {
        return new LayerResult(layer, SKIPPED, why, List.of());
    }

    public static LayerResult notInstalled(String layer, String what) {
        return new LayerResult(layer, NOT_INSTALLED, what, List.of());
    }

    public static LayerResult failed(String layer, String why) {
        return new LayerResult(layer, FAILED, why, List.of());
    }

    public List<SecurityFinding> findings() {
        return findings == null ? List.of() : findings;
    }
}
