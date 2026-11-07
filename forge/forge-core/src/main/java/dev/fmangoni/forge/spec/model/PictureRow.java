package dev.fmangoni.forge.spec.model;

/**
 * One row of a spec's pictures table: {@code | [image: picture] | state | `screenshot` |}.
 *
 * @param picture    the picture's path, relative to the repository, as the spec names it
 * @param state      what the picture shows, in the spec's words
 * @param screenshot where the builder's screenshot of that state is saved, relative to the repository
 */
public record PictureRow(String picture, String state, String screenshot) {
}
