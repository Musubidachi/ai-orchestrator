/**
 * Putting a family's approved versions on GitHub: one private repository per family, the initial
 * version on {@code main}, which never moves, and each enhancement on a branch of its own.
 *
 * <p><b>Belongs here:</b> making the repository the first time, pushing each approved version at
 * the commit its own work ends at, working that commit out for versions that finished before it was
 * recorded, the names of the repository and its branches, and the record beside the repository.
 *
 * <p><b>Not here:</b> Logging in, reading or holding a token, or installing {@code gh}: the person
 * does those, and git asks their {@code gh} for credentials itself. Force-pushing, ever. Deciding
 * when to publish: a person's approval or press of Publish does, through forge-pipeline.
 *
 * <p><b>Start with:</b> {@code Publisher.publish}, {@code Publication}, {@code Tips}.
 *
 * <p>Depends on: deploy, git, json, process, project, state.
 */
package dev.fmangoni.forge.deploy.github;
