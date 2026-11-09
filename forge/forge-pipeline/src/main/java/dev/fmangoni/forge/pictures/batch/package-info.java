/**
 * A picture batch's folder: finding it, reading its list, keeping its record, and working out where
 * each job stands.
 *
 * <p><b>Belongs here:</b> {@code Batches} (the folders under {@code runs/} with a {@code batch.json}),
 * {@code JobCards} (the list), {@code RecordStore} (Forge's {@code record.json}) and {@code JobStates}.
 *
 * <p><b>Not here:</b> Running a check or a placing, and writing the agent's list ({@code pictures.work}).
 *
 * <p><b>Start with:</b> {@code Batches.load}, {@code JobStates.of}.
 *
 * <p>Depends on: json, pictures.model.
 */
package dev.fmangoni.forge.pictures.batch;
