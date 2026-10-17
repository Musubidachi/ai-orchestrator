package dev.fmangoni.forge.ui.editor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.spec.model.FrontMatter;
import lombok.Data;

/** A spec as the editor holds it: the structured contract, the prose, and where it came from. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Draft {

    private FrontMatter front = new FrontMatter();

    private String body = "";

    /**
     * The file this draft was opened from, or blank for a new spec.
     *
     * <p>Carried so a save that changes the id or title can remove the old file. Without it,
     * renaming a spec would silently leave the previous version in the wishlist, and the next run
     * would build both.
     */
    private String originalFile = "";
}
