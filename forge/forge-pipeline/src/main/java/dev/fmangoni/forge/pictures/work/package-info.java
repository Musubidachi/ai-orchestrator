/**
 * What Forge does for a picture batch: checks each picture the agent saves, keeps what a person
 * says about it, places what is kept when a person clicks for it, and writes the agent's list.
 *
 * <p><b>Belongs here:</b> {@code PictureWatch} (the look every few seconds), {@code DraftChecks},
 * {@code Placing}, {@code Rulings}, {@code NextJobs} (the agent's list and prompt) and
 * {@code BatchCommand} (running one of a card's commands).
 *
 * <p><b>Not here:</b> Reading the folder and working out where each job stands ({@code pictures.batch});
 * what the dashboard shows ({@code pictures.view}).
 *
 * <p><b>Start with:</b> {@code PictureWatch.pass}.
 *
 * <p>Depends on: gate, pictures.batch, pictures.model, process, process.local.
 */
package dev.fmangoni.forge.pictures.work;
