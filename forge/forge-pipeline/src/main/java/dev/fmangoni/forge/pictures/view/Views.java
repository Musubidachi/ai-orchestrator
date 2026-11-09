package dev.fmangoni.forge.pictures.view;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Batches;
import dev.fmangoni.forge.pictures.batch.Draft;
import dev.fmangoni.forge.pictures.batch.JobStates;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Ruling;
import dev.fmangoni.forge.pictures.work.NextJobs;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Reads a batch folder into what a page shows.
 *
 * <p>Links point at {@code /runs/}, where the editor serves a run's files, with the picture's stamp
 * added so a browser never shows a picture or a review sheet from before it was drawn again.
 */
@UtilityClass
public class Views {

    /** The batch in this folder, or one saying why it cannot be read. */
    public BatchView of(Path folder) {
        String id = folder.getFileName().toString();
        try {
            return read(Batches.load(folder));
        } catch (RuntimeException e) {
            return BatchView.broken(id, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private BatchView read(Batch batch) {
        BatchRecord record = new RecordStore(batch.root()).read();
        List<Optional<Draft>> drafts = batch.drafts();
        List<JobState> states = JobStates.of(batch.jobs(), drafts, record);
        Map<JobState, Integer> counts = new EnumMap<>(JobState.class);
        for (JobState state : JobState.values()) {
            counts.put(state, 0);
        }
        List<JobView> jobs = new ArrayList<>();
        int placeable = 0;
        for (int i = 0; i < states.size(); i++) {
            PictureJob job = batch.jobs().get(i);
            counts.merge(states.get(i), 1, Integer::sum);
            jobs.add(job(batch, job, states.get(i), drafts.get(i), record));
            if (states.get(i) == JobState.KEPT && job.place() != null && record.find(job.draft()).getPlaceAsked() == null) {
                placeable++;
            }
        }
        String rules = batch.settings().rules();
        return new BatchView(batch.id(), batch.settings().title(), batch.settings().target(), batch.settings().agent(),
                rules == null ? null : link(batch, rules, null), counts, jobs, NextJobs.message(batch),
                batch.repo().resolve(NextJobs.promptFile(batch.settings())).toString().replace('\\', '/'), placeable, null);
    }

    private JobView job(Batch batch, PictureJob job, JobState state, Optional<Draft> draft, BatchRecord record) {
        JobRecord kept = record.find(job.draft());
        String stamp = draft.map(Draft::stamp).orElse(null);
        Optional<CommandRun> check = stamp == null ? Optional.empty() : kept.checkOf(stamp);
        String sheet = job.sheet() == null || check.isEmpty()
                ? null : link(batch, job.sheet(), String.valueOf(check.get().at().toEpochMilli()));
        RunView failed = state == JobState.KEPT && kept.getPlaceFailed() != null ? run(batch, kept.getPlaceFailed()) : null;
        return new JobView(job.number(), job.total(), job.title(), state, job.draft(), stamp,
                check.map(each -> run(batch, each)).orElse(null), sheet,
                stamp == null ? null : link(batch, job.draft(), stamp), NextJobs.prompt(job, state, record),
                kept.drawings(), kept.lastRuling().map(Ruling::note).orElse(null), kept.getPlaceAsked() != null, failed);
    }

    private RunView run(Batch batch, CommandRun run) {
        return new RunView(run.verdict(), run.exit(), run.lines(),
                run.log() == null ? null : link(batch, run.log(), String.valueOf(run.at().toEpochMilli())), run.at());
    }

    /** A link to a file in the batch folder, or null when there is no such file. @param version or null */
    private String link(Batch batch, String relative, String version) {
        Path file = batch.file(relative);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        StringBuilder url = new StringBuilder("/runs/").append(encode(batch.id()));
        for (Path part : batch.root().normalize().relativize(file)) {
            url.append('/').append(encode(part.toString()));
        }
        return version == null ? url.toString() : url.append("?v=").append(encode(version)).toString();
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
