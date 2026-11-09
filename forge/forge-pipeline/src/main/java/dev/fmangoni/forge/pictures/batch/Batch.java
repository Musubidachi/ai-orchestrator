package dev.fmangoni.forge.pictures.batch;

import dev.fmangoni.forge.pictures.model.BatchSettings;
import dev.fmangoni.forge.pictures.model.PictureJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One batch folder, read: its settings and its jobs.
 *
 * <p>The folder keeps what Forge and the person use — the list, the rules, the tools, the review
 * sheets and the record — beside {@code repo/}, the one folder the agent that draws works in. So the
 * agent can save pictures and can change neither the checks nor what they found.
 *
 * @param root the batch folder, which is under {@code runs/} and is named like a run
 */
public record Batch(Path root, BatchSettings settings, List<PictureJob> jobs) {

    /** What makes a folder under {@code runs/} a batch. */
    public static final String SETTINGS = "batch.json";

    public Batch {
        jobs = List.copyOf(jobs);
    }

    public String id() {
        return root.getFileName().toString();
    }

    /** The one folder the agent that draws works in. */
    public Path repo() {
        return root.resolve("repo");
    }

    /** A path the list gives, relative to the batch folder, and never one outside it. */
    public Path file(String relative) {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root.normalize())) {
            throw new IllegalArgumentException(relative + " is outside the batch folder");
        }
        return file;
    }

    /** The picture on disk for a job, if there is one. */
    public Optional<Draft> draft(PictureJob job) {
        try {
            BasicFileAttributes seen = Files.readAttributes(file(job.draft()), BasicFileAttributes.class);
            if (!seen.isRegularFile()) {
                return Optional.empty();
            }
            Instant modified = seen.lastModifiedTime().toInstant();
            return Optional.of(new Draft(modified.toEpochMilli() + "-" + seen.size(), modified));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + job.draft(), e);
        }
    }

    /** Every job's picture, in list order. */
    public List<Optional<Draft>> drafts() {
        return jobs.stream().map(this::draft).toList();
    }
}
