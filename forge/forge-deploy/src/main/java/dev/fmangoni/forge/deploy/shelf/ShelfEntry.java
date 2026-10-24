package dev.fmangoni.forge.deploy.shelf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One file a shelf carries.
 *
 * @param path   where it goes back to, with forward slashes: {@code runs/003/state.json},
 *               {@code git/config}, or {@code worktree/} and a path in the repository
 * @param size   its length, checked when it comes back
 * @param sha256 its checksum, checked when it comes back
 * @param parts  1, or how many pieces it was cut into to stay under GitHub's limit on one file
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelfEntry(String path, long size, String sha256, int parts) {
}
