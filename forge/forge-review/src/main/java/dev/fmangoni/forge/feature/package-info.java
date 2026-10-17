/**
 * The features a person approved from an agent's proposals, and the recordings that show them
 * working.
 *
 * <p><b>Belongs here:</b> {@code Feature}, the approved list and what each is still owed, the clips
 * still owed put as questions ({@code MissingClips}), and reading a recording's own bytes to tell a
 * real clip from a renamed file.
 *
 * <p><b>Not here:</b> Proposals (they are {@code plan.PlanFeature}) and the gate that demands the
 * recordings ({@code gate}).
 *
 * <p><b>Start with:</b> {@code Features}, {@code Feature}, {@code Recording}.
 *
 * <p>Depends on: plan, review.decision.model.
 */
package dev.fmangoni.forge.feature;
