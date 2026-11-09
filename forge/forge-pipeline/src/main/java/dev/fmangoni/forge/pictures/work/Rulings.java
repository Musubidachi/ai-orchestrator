package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Draft;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Ruling;
import dev.fmangoni.forge.pictures.model.Verdict;
import java.time.Instant;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Keeps what a person said about a picture: keep it, or draw it again and what to fix.
 *
 * <p>A ruling is about the picture the person saw, named by its stamp. If the agent has saved a new
 * one since, the ruling is refused rather than applied to a picture nobody looked at.
 */
@UtilityClass
public class Rulings {

    /** Saves a ruling. @return why it was not saved, or empty when it was */
    public Optional<String> save(Batch batch, String draft, String stamp, boolean keep, String note) {
        Optional<PictureJob> job = batch.jobs().stream().filter(each -> each.draft().equals(draft)).findFirst();
        if (job.isEmpty()) {
            return Optional.of("no job saves a picture as " + draft);
        }
        String said = note == null || note.isBlank() ? null : note.strip();
        if (!keep && said == null) {
            return Optional.of("say what to fix: it is added to the prompt");
        }
        Optional<String> now = batch.draft(job.get()).map(Draft::stamp);
        if (now.isEmpty()) {
            return Optional.of("the picture is gone");
        }
        if (!now.get().equals(stamp)) {
            return Optional.of("it was drawn again since: review the new picture");
        }
        return new RecordStore(batch.root()).update(record -> {
            JobRecord kept = record.find(draft);
            Optional<CommandRun> check = kept.checkOf(stamp);
            if (check.isEmpty()) {
                return Optional.of("it has not been checked yet");
            }
            if (kept.rulingOf(stamp).isPresent()) {
                return Optional.of("it was already reviewed");
            }
            if (keep && said == null && !passed(check.get())) {
                return Optional.of("the check found a problem: say why it stands");
            }
            record.edit(draft).getRulings().add(new Ruling(stamp, Instant.now(), keep, said));
            return Optional.<String>empty();
        });
    }

    /** Whether a check passed, with or without something to look at. */
    public boolean passed(CommandRun check) {
        return check.verdict() == Verdict.OK || check.verdict() == Verdict.LOOK;
    }
}
