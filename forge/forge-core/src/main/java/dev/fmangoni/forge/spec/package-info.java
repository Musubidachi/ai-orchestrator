/**
 * Wishlist entries: reading them, writing them back, and the wishlist as a whole.
 *
 * <p><b>Belongs here:</b> {@code SpecLoader} and {@code SpecWriter}, the refusal a bad entry earns,
 * and {@code Wishlist}, which resolves an improvement's ancestry.
 *
 * <p><b>Not here:</b> The entry's own data ({@code spec.model}), the ordering ({@code spec.rank})
 * and the model ladder ({@code spec.tier}). Nothing here acts on what a spec says.
 *
 * <p><b>Start with:</b> {@code SpecLoader.load}, {@code Wishlist}.
 *
 * <p>Depends on: spec.model.
 */
package dev.fmangoni.forge.spec;
