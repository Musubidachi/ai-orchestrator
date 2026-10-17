/**
 * Where a run lives on disk and the small facts about it that depend on nothing else.
 *
 * <p><b>Belongs here:</b> The directory layout of Forge and of one run ({@code ForgeHome}, {@code
 * RunPaths}), the pipeline {@code Phase} and its progress bar, a turn's time budget, disk usage,
 * and the dashboard's own settings.
 *
 * <p><b>Not here:</b> The persisted state of a run, which records gate and review results: that is
 * {@code state}, one layer up. Nothing here may import a capability package.
 *
 * <p><b>Start with:</b> {@code RunPaths}, {@code Phase}, {@code TimeBudget}, {@code ForgeHome}.
 *
 * <p>Depends on: json.
 */
package dev.fmangoni.forge.project;
