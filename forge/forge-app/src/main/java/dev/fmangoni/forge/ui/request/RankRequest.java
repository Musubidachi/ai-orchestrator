package dev.fmangoni.forge.ui.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** A request to renumber the wishlist, optionally moving one spec first. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RankRequest {

    /** The spec to move, or blank to renumber the list as it already stands. */
    private String id = "";

    /** -1 to move it earlier, +1 later. Movement is confined to its own group. */
    private int delta;
}
