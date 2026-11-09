package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Draft;
import dev.fmangoni.forge.pictures.batch.JobStates;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Verdict;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Places kept pictures in the project they were drawn for, when a person clicks for it.
 *
 * <p>Placing is the card's own command. For the event screens that is the art tools' accept, which
 * checks the picture again, copies it into the project's pictures and, with an event's sixth
 * screen, brings the event's spec up to date. So it changes another project's files, and runs only
 * for a click: the click marks what is kept, and the watcher runs the commands one at a time, in
 * list order. When a person kept a picture the check found a problem with, the batch's
 * {@code anyway} word is added, as the card's rules ask.
 */
@UtilityClass
public class Placing {

    private final Duration TIMEOUT = Duration.ofMinutes(5);

    /** Marks every kept picture to be placed that is not marked yet. @return how many were */
    public int ask(Batch batch, RecordStore store) {
        return store.update(record -> {
            List<Optional<Draft>> drafts = batch.drafts();
            List<JobState> states = JobStates.of(batch.jobs(), drafts, record);
            int asked = 0;
            for (int i = 0; i < states.size(); i++) {
                PictureJob job = batch.jobs().get(i);
                if (states.get(i) == JobState.KEPT && job.place() != null && record.find(job.draft()).getPlaceAsked() == null) {
                    record.edit(job.draft()).setPlaceAsked(drafts.get(i).orElseThrow().stamp());
                    asked++;
                }
            }
            return asked;
        });
    }

    /** Runs the placings asked for. @return how many ran */
    public int run(Batch batch, RecordStore store) {
        BatchRecord record = store.read();
        List<Optional<Draft>> drafts = batch.drafts();
        List<JobState> states = JobStates.of(batch.jobs(), drafts, record);
        int ran = 0;
        for (int i = 0; i < states.size(); i++) {
            PictureJob job = batch.jobs().get(i);
            JobRecord kept = record.find(job.draft());
            String asked = kept.getPlaceAsked();
            if (asked == null) {
                continue;
            }
            // A picture drawn again since the click is not the one that was kept: it is checked and
            // reviewed first, and the click is spent.
            boolean same = states.get(i) == JobState.KEPT
                    && drafts.get(i).map(Draft::stamp).filter(asked::equals).isPresent();
            CommandRun run = same
                    ? BatchCommand.run(batch.root(), "place-" + BatchCommand.slug(job.draft()),
                            command(batch, job, kept, asked), asked, TIMEOUT)
                    : null;
            store.update(latest -> settle(latest.edit(job.draft()), run));
            ran += run == null ? 0 : 1;
        }
        return ran;
    }

    private String command(Batch batch, PictureJob job, JobRecord kept, String stamp) {
        boolean overruled = kept.checkOf(stamp).map(check -> check.verdict() == Verdict.PROBLEM).orElse(false);
        String anyway = batch.settings().anyway();
        return overruled && anyway != null && !anyway.isBlank() ? job.place() + " " + anyway : job.place();
    }

    private CommandRun settle(JobRecord job, CommandRun run) {
        job.setPlaceAsked(null);
        if (run != null && run.verdict() == Verdict.OK) {
            job.setPlaced(run);
            job.setPlaceFailed(null);
        } else if (run != null) {
            job.setPlaceFailed(run);
        }
        return run;
    }
}
