/**
 * Picture batches: pictures an agent outside Forge draws, run like a project's jobs. Forge writes
 * the agent's list and prompt in the folder it works in, checks each picture it saves, deals the
 * review sheets out for a quick review, and places what a person keeps.
 *
 * <p><b>Belongs here:</b> {@code PictureBatches}, the one way in.
 *
 * <p><b>Not here:</b> Everything else, which is below: {@code pictures.model} (the records),
 * {@code pictures.batch} (the folder), {@code pictures.work} (what Forge does) and
 * {@code pictures.view} (what a page shows).
 *
 * <p><b>Start with:</b> {@code PictureBatches}; {@code forge/ENHANCEMENTS.md} item 6 says why.
 *
 * <p>Depends on: pictures.batch, pictures.view, pictures.work.
 */
package dev.fmangoni.forge.pictures;
