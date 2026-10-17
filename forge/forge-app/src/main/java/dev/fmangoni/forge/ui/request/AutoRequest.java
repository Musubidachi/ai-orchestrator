package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Turns automatic queue-filling on or off. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutoRequest {

    private boolean enabled;
}
