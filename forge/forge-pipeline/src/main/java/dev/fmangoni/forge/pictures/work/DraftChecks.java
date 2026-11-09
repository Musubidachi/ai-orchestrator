package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Draft;
import dev.fmangoni.forge.pictures.batch.JobStates;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.PictureJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Checks each picture the agent saved, with its card's check command.
 *
 * <p>Only once the picture has not been written for two seconds, so a check never reads one still
 * being saved. One at a time, because each writes the review sheet a person will read.
 */
@UtilityClass
public class DraftChecks {

    private final Duration SETTLE = Duration.ofSeconds(2);

    private final Duration TIMEOUT = Duration.ofMinutes(5);

    /** Checks every picture that has not been checked. @return how many were */
    public int run(Batch batch, RecordStore store) {
        List<Optional<Draft>> drafts = batch.drafts();
        List<JobState> states = JobStates.of(batch.jobs(), drafts, store.read());
        Instant settled = Instant.now().minus(SETTLE);
        int checked = 0;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i) != JobState.DRAWN || drafts.get(i).orElseThrow().modified().isAfter(settled)) {
                continue;
            }
            PictureJob job = batch.jobs().get(i);
            CommandRun run = BatchCommand.run(batch.root(), "check-" + BatchCommand.slug(job.draft()), job.check(),
                    drafts.get(i).orElseThrow().stamp(), TIMEOUT);
            store.update(record -> record.edit(job.draft()).getChecks().add(run));
            checked++;
        }
        return checked;
    }
}
