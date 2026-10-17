/**
 * The vendor league table: what each vendor built and reviewed, scored and ranked for the dashboard.
 *
 * <p><b>Belongs here:</b> {@code League}, which reads the runs; {@code Tally}, which counts and
 * holds the scoring; and {@code Standing}, the row the page receives.
 *
 * <p><b>Not here:</b> Anything that acts on the result. The table is for a person to look at. It
 * must never choose a vendor, reach a brief, or be shown to an agent.
 *
 * <p><b>Start with:</b> {@code League.of}, {@code Tally} for the points.
 *
 * <p>Depends on: agent.model, project, review, review.model, state.
 */
package dev.fmangoni.forge.status.league;
