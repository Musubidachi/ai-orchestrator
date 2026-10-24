package dev.fmangoni.forge.deploy.shelf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Everything a shelf carries besides the project's own history, and how to put it back.
 *
 * @param files   every file, with its size and checksum
 * @param deleted files the working tree had deleted without a commit, deleted again on resume
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelfManifest(String family, List<String> versions, String head, String localBranch,
                            List<ShelfEntry> files, List<String> deleted) {

    public ShelfManifest {
        files = files == null ? List.of() : List.copyOf(files);
        deleted = deleted == null ? List.of() : List.copyOf(deleted);
    }
}
