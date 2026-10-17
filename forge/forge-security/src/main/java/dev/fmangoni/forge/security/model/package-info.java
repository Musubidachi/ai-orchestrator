/**
 * What a security check found, as it is persisted in {@code security.json}.
 *
 * <p><b>Belongs here:</b> The report, one finding, and what each layer did (ran, skipped, not
 * installed, failed).
 *
 * <p><b>Not here:</b> Running a layer. A finding's severity and whether it is serious are decided
 * by the layer that raises it.
 *
 * <p><b>Start with:</b> {@code SecurityReport}, {@code SecurityFinding}, {@code LayerResult}.
 *
 * <p>Depends on: json.
 */
package dev.fmangoni.forge.security.model;
