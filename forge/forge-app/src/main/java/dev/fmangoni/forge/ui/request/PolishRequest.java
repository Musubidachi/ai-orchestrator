package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** A press on the Polish page: asking the models for designs, or building one. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolishRequest {

    private String id = "";

    /** What the person particularly wants, or blank. */
    private String note = "";

    /** The design picked, or blank: to ask for designs, or to leave the look to the agent. */
    private String option = "";

    /** When asking: the design to ask for more like, with only what the note says changed, or blank. */
    private String base = "";

    /** An extra to add beside the current look (theme, variants, animation), or blank to replace it. */
    private String extra = "";
}
