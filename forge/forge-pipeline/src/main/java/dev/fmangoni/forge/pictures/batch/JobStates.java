package dev.fmangoni.forge.pictures.batch;

import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Ruling;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Where each job stands, from its picture on disk and the record.
 *
 * <p>A check or a ruling counts only for the picture it was about, found by the picture's stamp: a
 * job drawn again is checked again and reviewed again. Placing is the exception. Once a picture is
 * placed the job stays done even if its picture is then removed; only a new one starts it again.
 */
@UtilityClass
public class JobStates {

    /** Each job's state, in list order. */
    public List<JobState> of(List<PictureJob> jobs, List<Optional<Draft>> drafts, BatchRecord record) {
        List<JobState> states = new ArrayList<>();
        boolean held = false;
        for (int i = 0; i < jobs.size(); i++) {
            PictureJob job = jobs.get(i);
            JobState state = own(drafts.get(i), record.find(job.draft()));
            if (state == JobState.TO_DRAW && (held || waitsFor(job, jobs, states))) {
                state = JobState.WAITING;
            }
            states.add(state);
            if (job.stop() && !states.stream().allMatch(JobStates::done)) {
                held = true;
            }
        }
        return states;
    }

    /** Kept or placed: nothing more is wanted of the job before the ones after it go ahead. */
    public boolean done(JobState state) {
        return state == JobState.KEPT || state == JobState.PLACED;
    }

    /** Where a job stands on its own, before what it waits for. */
    JobState own(Optional<Draft> draft, JobRecord record) {
        Optional<String> stamp = draft.map(Draft::stamp);
        if (record.getPlaced() != null && stamp.map(record.getPlaced().draft()::equals).orElse(true)) {
            return JobState.PLACED;
        }
        if (stamp.isEmpty()) {
            // A redraw asked for still stands when the old picture has been cleared away first.
            return record.lastRuling().filter(ruling -> !ruling.keep()).isPresent() ? JobState.REDRAW : JobState.TO_DRAW;
        }
        Optional<Ruling> ruling = record.rulingOf(stamp.get());
        if (ruling.isPresent()) {
            return ruling.get().keep() ? JobState.KEPT : JobState.REDRAW;
        }
        return record.checkOf(stamp.get()).isPresent() ? JobState.CHECKED : JobState.DRAWN;
    }

    /** Whether the job its card names, by the start of that job's title, is not kept yet. */
    private boolean waitsFor(PictureJob job, List<PictureJob> jobs, List<JobState> earlier) {
        if (job.after() == null) {
            return false;
        }
        for (int i = 0; i < earlier.size(); i++) {
            if (names(job.after(), jobs.get(i).title())) {
                return !done(earlier.get(i));
            }
        }
        return false;
    }

    /** Whether a title starts with the name, as whole words: "Ash Gate" names "Ash Gate, dusk" but not "Ash Gates". */
    private boolean names(String name, String title) {
        return title.startsWith(name)
                && (title.length() == name.length() || !Character.isLetterOrDigit(title.charAt(name.length())));
    }
}
