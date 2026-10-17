/**
 * The wishlist as the editor sees it: listing, opening, validating and saving specs.
 *
 * <p><b>Belongs here:</b> {@code SpecApi} and the shapes it returns — a row in the sidebar, the
 * result of a save, and a draft as the form holds it.
 *
 * <p><b>Not here:</b> Serving anything. Validation goes through the same loader the overnight run
 * uses, which is the whole reason the editor is hosted by Forge rather than static.
 *
 * <p><b>Start with:</b> {@code SpecApi.save}, {@code Draft}.
 *
 * <p>Depends on: project, spec, spec.model, state.
 */
package dev.fmangoni.forge.ui.editor;
