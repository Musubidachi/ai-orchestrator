package dev.fmangoni.forge.spec.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import lombok.Data;

/**
 * A deploy the person runs by pressing DeploySettings on an accepted project — never Forge on its own.
 *
 * <p>The command uses whatever CLI it names, logged in by the person beforehand. Forge holds no
 * credential for any host and never runs a login.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeploySettings {

    /** Run from the repository root, e.g. {@code npx wrangler pages deploy dist}. */
    private String command = "";

    /** Where it ends up; blank to take the first https URL the command prints. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String url = "";

    /** Executables that must be on PATH before DeploySettings is offered. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> requires = List.of();

    /** A command that exits zero when the deployed site is healthy; blank to GET the URL. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String check = "";
}
