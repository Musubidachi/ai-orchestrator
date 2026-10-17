/**
 * The page every report is rendered into, and the escaping every section goes through.
 *
 * <p><b>Belongs here:</b> {@code Html}: the shared page shell, and escaping text that came from a
 * model, a file name or a person.
 *
 * <p><b>Not here:</b> Any section's own content. Below the sections rather than beside the pages,
 * so that a section and the page that includes it do not point at each other.
 *
 * <p><b>Start with:</b> {@code Html.page}, {@code Html.escape}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.report.shell;
