package dev.fmangoni.forge.pictures.view;

import dev.fmangoni.forge.pictures.model.JobState;

/**
 * One job, as the dashboard and the review show it.
 *
 * @param number      where it stands on the list, from 1
 * @param total       how many jobs the list has
 * @param title       what its card calls it
 * @param state       where it stands
 * @param draft       where its picture is saved, relative to the batch folder; it names the job
 * @param stamp       the picture on disk now, which a ruling must name, or null
 * @param check       the check of that picture, or null
 * @param sheet       a link to the review sheet that check wrote, or null
 * @param picture     a link to the picture, or null
 * @param prompt      the prompt the agent is given now, with the note when it is drawn again
 * @param drawings    how many different pictures were checked
 * @param note        what the person last said about it, or null
 * @param placing     whether a placing was asked for and has not run yet
 * @param placeFailed the last placing, while a kept picture's placing has failed, or null
 */
public record JobView(int number, int total, String title, JobState state, String draft, String stamp,
                      RunView check, String sheet, String picture, String prompt, int drawings, String note,
                      boolean placing, RunView placeFailed) {
}
