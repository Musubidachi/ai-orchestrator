package dev.fmangoni.forge.pipeline.run;

/**
 * The project's copy of its spec, brought up to date with the wishlist, and what that changed.
 *
 * @param file the copy in the repository: {@code SPEC.md}, or {@code IMPROVEMENT.md} for an
 *             improvement
 * @param diff the change, as a unified diff of that file
 */
public record SpecChange(String file, String diff) {
}
