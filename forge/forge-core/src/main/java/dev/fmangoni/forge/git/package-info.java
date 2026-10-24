/**
 * The git operations Forge needs, and no more: commits, trees, diffs for review, and one process
 * per repository.
 *
 * <p><b>Belongs here:</b> {@code Git}, the repository lock, and turning a diff into sections a
 * model can read in one turn.
 *
 * <p><b>Not here:</b> Deciding what to commit or when; callers decide. Nothing that pushes:
 * publishing to GitHub is forge-deploy's, on a person's say-so, and it borrows the guard settings
 * from here ({@code Git.untrusted}) rather than repeating them.
 *
 * <p><b>Start with:</b> {@code Git}, {@code RepoLock.tryAcquire}, {@code ReviewDiff}.
 *
 * <p>Depends on: process, project.
 */
package dev.fmangoni.forge.git;
