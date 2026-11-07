/**
 * What changed in a project's screenshots since the version of its family a person accepted last,
 * shown in the report as red over the parts that changed shape. Informational: it decides nothing.
 *
 * <p><b>Belongs here:</b> finding the last accepted version and its screenshots, comparing two
 * screenshots cell by cell, and drawing and showing the result.
 *
 * <p><b>Not here:</b> holding a look still, which fails the gate and is {@code gate.LookCheck}.
 *
 * <p><b>Start with:</b> {@code ScreenChangesSection}.
 *
 * <p>Depends on: project, report.shell, state.
 */
package dev.fmangoni.forge.report.changes;
