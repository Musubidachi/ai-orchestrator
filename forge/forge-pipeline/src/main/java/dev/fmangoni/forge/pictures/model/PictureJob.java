package dev.fmangoni.forge.pictures.model;

import java.util.List;

/**
 * One job on a batch's list, as its card gives it: a picture to draw, the command that checks it,
 * and the one that places it once a person keeps it.
 *
 * @param number where it stands on the list, from 1
 * @param total  how many jobs the list has
 * @param title  what the card calls it, such as "Lantern Fair, Market"
 * @param after  the start of another job's title that must be kept before this one is drawn, or null
 * @param stop   whether a STOP line follows it: every later job waits until all up to it are kept
 * @param attach the files to attach, in order
 * @param prompt the prompt, exactly as written
 * @param draft  where the picture is saved, relative to the batch folder; it names the job in the record
 * @param check  the command that checks the picture, run from the batch folder
 * @param sheet  the review sheet the check writes, relative to the batch folder, or null
 * @param place  the command that places a kept picture, or null when the card has none
 */
public record PictureJob(int number, int total, String title, String after, boolean stop, List<String> attach,
                         String prompt, String draft, String check, String sheet, String place) {

    public PictureJob {
        attach = List.copyOf(attach);
    }
}
