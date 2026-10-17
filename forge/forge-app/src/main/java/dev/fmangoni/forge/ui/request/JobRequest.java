package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Names the project a start or stop applies to. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRequest {

    private String id = "";
}
