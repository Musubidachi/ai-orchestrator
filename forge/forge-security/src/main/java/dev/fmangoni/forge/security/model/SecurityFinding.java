package dev.fmangoni.forge.security.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One thing a security layer found.
 *
 * @param layer    which check raised it, e.g. {@code secrets}
 * @param severity {@code critical}, {@code high}, {@code medium}, {@code low} or {@code info}
 * @param title    what it is, in a line
 * @param location a file and line, a dependency, or a URL on the local build
 * @param evidence what was seen, with any secret value redacted
 * @param serious  whether it earns a repair turn and a ruling before acceptance — decided by the
 *                 layer that raised it, because only that layer knows how sure it is
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityFinding(String layer, String severity, String title, String location,
                              String evidence, boolean serious) {

    /**
     * The secret scanner's layer name.
     *
     * <p>Here rather than only on the scanner because the report filters on it: a secret in history
     * needs a ruling even though it is not serious. A name the report compares against is part of
     * what the report means, and a model that had to reach into a layer to know its own contents was
     * the one loop left inside this package.
     */
    public static final String SECRETS_LAYER = "secrets";

    public static final String CRITICAL = "critical";
    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
    public static final String INFO = "info";

    /** Critical first, for sorting and for the one-word summary on a card. */
    public int rank() {
        return switch (severity == null ? "" : severity) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
            default -> 4;
        };
    }
}
