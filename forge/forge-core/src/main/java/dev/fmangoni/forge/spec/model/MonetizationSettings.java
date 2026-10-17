package dev.fmangoni.forge.spec.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * Ads in the app: {@code none}, {@code test} slots while building, or {@code live} in deploys.
 *
 * <p>Ids here are public identifiers — a publisher id, an ad unit id — and are only ever handed
 * to a deploy build. Gates, tests and recordings always see test mode, so nothing Forge runs
 * produces an impression on a real account.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonetizationSettings {

    private String ads = "none";

    /** {@code adsense}, {@code admob} or {@code custom}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String provider = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> ids = new LinkedHashMap<>();
}
