package dev.fmangoni.forge.ui.editor;






















/** A row in the editor's sidebar. Invalid specs are listed too, with the reason. */
public record SpecSummary(String file, String id, String title, String assign,
                      boolean valid, String error) {
}
