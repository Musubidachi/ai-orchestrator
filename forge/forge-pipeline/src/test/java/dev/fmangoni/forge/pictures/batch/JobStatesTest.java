package dev.fmangoni.forge.pictures.batch;

import static dev.fmangoni.forge.pictures.model.JobState.CHECKED;
import static dev.fmangoni.forge.pictures.model.JobState.DRAWN;
import static dev.fmangoni.forge.pictures.model.JobState.KEPT;
import static dev.fmangoni.forge.pictures.model.JobState.PLACED;
import static dev.fmangoni.forge.pictures.model.JobState.REDRAW;
import static dev.fmangoni.forge.pictures.model.JobState.TO_DRAW;
import static dev.fmangoni.forge.pictures.model.JobState.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Ruling;
import dev.fmangoni.forge.pictures.model.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobStatesTest {

    private static final Optional<Draft> NONE = Optional.empty();

    @Test
    @DisplayName("a job goes from drawing to checked to reviewed, each step about the picture on disk")
    void ownStates() {
        JobRecord record = new JobRecord();
        assertEquals(TO_DRAW, JobStates.own(NONE, record));
        assertEquals(DRAWN, JobStates.own(drawn("1-10"), record));

        record.getChecks().add(check("1-10"));
        assertEquals(CHECKED, JobStates.own(drawn("1-10"), record));
        assertEquals(DRAWN, JobStates.own(drawn("2-11"), record), "a picture drawn again is checked again");

        record.getRulings().add(ruling("1-10", true));
        assertEquals(KEPT, JobStates.own(drawn("1-10"), record));
        record.getChecks().add(check("2-11"));
        assertEquals(CHECKED, JobStates.own(drawn("2-11"), record), "and reviewed again: the keep was for the last one");
    }

    @Test
    @DisplayName("a redraw stands until a new picture comes, even when the old one was cleared away first")
    void redraw() {
        JobRecord record = new JobRecord();
        record.getChecks().add(check("1-10"));
        record.getRulings().add(ruling("1-10", false));

        assertEquals(REDRAW, JobStates.own(drawn("1-10"), record));
        assertEquals(REDRAW, JobStates.own(NONE, record));
        assertEquals(DRAWN, JobStates.own(drawn("2-11"), record));
    }

    @Test
    @DisplayName("a placed job stays placed when its picture is removed; only a new picture starts it again")
    void placedStays() {
        JobRecord record = new JobRecord();
        record.getChecks().add(check("1-10"));
        record.getRulings().add(ruling("1-10", true));
        record.setPlaced(check("1-10"));

        assertEquals(PLACED, JobStates.own(drawn("1-10"), record));
        assertEquals(PLACED, JobStates.own(NONE, record));
        assertEquals(DRAWN, JobStates.own(drawn("2-11"), record));
    }

    @Test
    @DisplayName("a STOP holds every later job that is not drawn yet, until every job up to it is kept")
    void stopHolds() {
        List<PictureJob> jobs = List.of(job(1, "Ash Gate", null, false), job(2, "Ash Road", null, true),
                job(3, "Ash Bridge", null, false), job(4, "Ash Tower", null, false));
        BatchRecord record = new BatchRecord();
        reviewed(record, jobs.get(1), "2-20", true);

        assertEquals(List.of(TO_DRAW, KEPT, WAITING, WAITING), JobStates.of(jobs, drafts(NONE, drawn("2-20"), NONE, NONE), record),
                "job 1 is not kept yet, so the STOP after job 2 still holds");

        reviewed(record, jobs.get(0), "1-10", true);
        assertEquals(List.of(KEPT, KEPT, TO_DRAW, TO_DRAW),
                JobStates.of(jobs, drafts(drawn("1-10"), drawn("2-20"), NONE, NONE), record));
    }

    @Test
    @DisplayName("a picture saved behind a STOP is still checked and reviewed: holding is for what is not drawn")
    void heldButDrawn() {
        List<PictureJob> jobs = List.of(job(1, "Ash Gate", null, true), job(2, "Ash Road", null, false));
        BatchRecord record = new BatchRecord();

        assertEquals(List.of(DRAWN, DRAWN), JobStates.of(jobs, drafts(drawn("1-10"), drawn("2-20")), record));
    }

    @Test
    @DisplayName("a job waits for the one it names, by whole words of its title, until that one is kept or placed")
    void after() {
        List<PictureJob> jobs = List.of(job(1, "Ash Gate, dusk", null, false), job(2, "Ash Gates", null, false),
                job(3, "Ash Road", "Ash Gate", false), job(4, "Ash Bridge", "Ash Gates", false));
        BatchRecord record = new BatchRecord();
        record.edit(jobs.get(0).draft()).getChecks().add(check("1-10"));
        record.edit(jobs.get(1).draft()).getChecks().add(check("2-20"));
        List<Optional<Draft>> drafts = drafts(drawn("1-10"), drawn("2-20"), NONE, NONE);

        assertEquals(List.of(CHECKED, CHECKED, WAITING, WAITING), JobStates.of(jobs, drafts, record));

        record.edit(jobs.get(0).draft()).getRulings().add(ruling("1-10", true));
        assertEquals(List.of(KEPT, CHECKED, TO_DRAW, WAITING), JobStates.of(jobs, drafts, record),
                "\"Ash Gate\" names the first job, not \"Ash Gates\"");

        record.edit(jobs.get(1).draft()).setPlaced(check("2-20"));
        assertEquals(List.of(KEPT, PLACED, TO_DRAW, TO_DRAW), JobStates.of(jobs, drafts, record));
    }

    private static PictureJob job(int number, String title, String after, boolean stop) {
        return new PictureJob(number, 4, title, after, stop, List.of(), "Paint " + title + ".",
                "repo/drafts/" + number + ".png", "echo ok", null, null);
    }

    private static Optional<Draft> drawn(String stamp) {
        return Optional.of(new Draft(stamp, Instant.EPOCH));
    }

    @SafeVarargs
    private static List<Optional<Draft>> drafts(Optional<Draft>... each) {
        return new ArrayList<>(Arrays.asList(each));
    }

    private static CommandRun check(String stamp) {
        return new CommandRun(stamp, Instant.EPOCH, 0, Verdict.OK, List.of(), null);
    }

    private static Ruling ruling(String stamp, boolean keep) {
        return new Ruling(stamp, Instant.EPOCH, keep, keep ? null : "Make it brighter.");
    }

    private static void reviewed(BatchRecord record, PictureJob job, String stamp, boolean keep) {
        record.edit(job.draft()).getChecks().add(check(stamp));
        record.edit(job.draft()).getRulings().add(ruling(stamp, keep));
    }
}
