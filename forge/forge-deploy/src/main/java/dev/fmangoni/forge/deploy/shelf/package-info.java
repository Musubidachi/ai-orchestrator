/**
 * Shelving a whole family on GitHub to free this machine, and resuming it here later.
 *
 * <p><b>Belongs here:</b> the shelf branch and the payload on it (Forge's files for every version,
 * logs cut into pieces under GitHub's limit, the repository's git settings and what its history does
 * not hold), checking all of it on the way back, deleting only after GitHub holds the shelf, and the
 * mark each version keeps here while it is away.
 *
 * <p><b>Not here:</b> Logging in, reading or holding a token. Deciding when: a person's press of
 * Shelve or Resume, through forge-pipeline. Force-pushing, ever.
 *
 * <p><b>Start with:</b> {@code Shelver.shelve}, {@code Shelver.resume}, {@code ShelfFiles}.
 *
 * <p>Depends on: deploy.github, git, json, process, project, state.
 */
package dev.fmangoni.forge.deploy.shelf;
