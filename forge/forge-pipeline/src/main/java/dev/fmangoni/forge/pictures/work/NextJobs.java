package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.JobStates;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.BatchSettings;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.PictureJob;
import dev.fmangoni.forge.pictures.model.Ruling;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Writes what the agent that draws reads, in the folder it works in.
 *
 * <p>{@code next-jobs.md} holds just the jobs to draw now: each one's attachments, its prompt and
 * where to save the picture, with every path written out in full. A job to draw again has the
 * person's note added to its prompt, as the one sentence that says what to fix. The prompt file
 * holds the one message a person sends the agent, which never changes: the list does. So after a
 * usage limit the same message carries on where the agent stopped.
 */
@UtilityClass
public class NextJobs {

    public final String LIST = "next-jobs.md";

    /** The prompt file's name, from who draws: {@code astra-prompt.md}. */
    public String promptFile(BatchSettings settings) {
        String name = settings.agent().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (name.isEmpty() ? "agent" : name) + "-prompt.md";
    }

    /** The message to send the agent that draws. */
    public String message(Batch batch) {
        String repo = slashes(batch.repo());
        return "Read " + repo + "/" + LIST + " and draw every job it lists, in order. Then stop.\n\n"
                + "Use only the built-in image tool, one call per picture. Attach the files in the order shown and "
                + "paste each prompt exactly as written: do not reword, shorten or add to it. Save each picture "
                + "exactly where its job says, over any picture already there. Run nothing and change no other "
                + "file: Forge checks each picture as it is saved. The files to attach are only read, wherever "
                + "they are; everything you write goes inside " + repo + ".\n\n"
                + "Ask before downloading or installing anything. If something is blocked, stop and report it; "
                + "don't work around it. On HTTP 429 usage_limit_reached, stop at once.\n\n"
                + "When you stop, for any reason, report which pictures you saved and which you did not.";
    }

    /** The prompt for a job, with the note added when a person asked for it to be drawn again. */
    public String prompt(PictureJob job, JobState state, BatchRecord record) {
        if (state != JobState.REDRAW) {
            return job.prompt();
        }
        String note = record.find(job.draft()).lastRuling().map(Ruling::note).orElse(null);
        return note == null || note.isBlank() ? job.prompt() : job.prompt() + " " + sentence(note);
    }

    /** Writes both files, each only when it changed, and makes the folders the pictures go in. */
    public synchronized void write(Batch batch) {
        BatchRecord record = new RecordStore(batch.root()).read();
        List<JobState> states = JobStates.of(batch.jobs(), batch.drafts(), record);
        try {
            Files.createDirectories(batch.repo());
            for (int i = 0; i < states.size(); i++) {
                if (wanted(states.get(i))) {
                    Files.createDirectories(batch.file(batch.jobs().get(i).draft()).getParent());
                }
            }
            save(batch.repo().resolve(LIST), list(batch, states, record));
            save(batch.repo().resolve(promptFile(batch.settings())), promptPage(batch));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the next jobs for " + batch.id(), e);
        }
    }

    /** Whether the agent is asked to draw the job now. */
    public boolean wanted(JobState state) {
        return state == JobState.TO_DRAW || state == JobState.REDRAW;
    }

    private String list(Batch batch, List<JobState> states, BatchRecord record) {
        StringBuilder page = new StringBuilder("# Next jobs: ").append(batch.settings().title()).append("\n\n");
        long count = states.stream().filter(NextJobs::wanted).count();
        if (count == 0) {
            page.append("Nothing to draw right now. Stop here.\n");
        } else {
            page.append(count == 1 ? "Draw this job: attach" : "Draw these " + count + " jobs, in order. For each, attach")
                    .append(" the files in the order shown, paste the prompt exactly as written, ")
                    .append("and save the picture where it says, over any picture already there.\n");
            for (int i = 0; i < states.size(); i++) {
                if (wanted(states.get(i))) {
                    card(page, batch, batch.jobs().get(i), states.get(i), record);
                }
            }
            page.append(count == 1 ? "\nThat is all for now. Stop once it is saved.\n"
                    : "\nThat is all for now. Stop once these are saved.\n");
        }
        String later = later(states);
        return later.isEmpty() ? page.toString() : page.append("\n").append(later).append("\n").toString();
    }

    private void card(StringBuilder page, Batch batch, PictureJob job, JobState state, BatchRecord record) {
        page.append("\n## Job ").append(job.number()).append(" of ").append(job.total()).append(": ")
                .append(job.title()).append("\n\n");
        if (state == JobState.REDRAW) {
            page.append("Drawn again: the prompt ends with what to fix.\n\n");
        }
        if (!job.attach().isEmpty()) {
            page.append("Attach, in this order:\n\n");
            for (int n = 0; n < job.attach().size(); n++) {
                page.append(n + 1).append(". `").append(attachment(batch, job.attach().get(n))).append("`\n");
            }
            page.append("\n");
        }
        page.append("Prompt:\n\n```text\n").append(prompt(job, state, record)).append("\n```\n\n");
        page.append("Save the picture as `").append(slashes(batch.file(job.draft()))).append("`.\n");
    }

    private String later(List<JobState> states) {
        long waiting = states.stream().filter(state -> state == JobState.WAITING).count();
        long review = states.stream().filter(state -> state == JobState.DRAWN || state == JobState.CHECKED).count();
        StringBuilder text = new StringBuilder();
        if (waiting > 0) {
            text.append(waiting).append(waiting == 1 ? " more job waits" : " more jobs wait")
                    .append(" until earlier pictures are kept.");
        }
        if (review > 0) {
            text.append(text.isEmpty() ? "" : " ").append(review).append(review == 1 ? " picture waits" : " pictures wait")
                    .append(" for a review.");
        }
        return text.toString();
    }

    private String promptPage(Batch batch) {
        return "# " + batch.settings().agent() + "'s prompt: " + batch.settings().title() + "\n\n"
                + "Start " + batch.settings().agent() + " in `" + slashes(batch.repo()) + "` and send it the prompt "
                + "below. Send the same prompt again after a usage limit, or when the dashboard says there is more "
                + "to draw: the list it reads always holds just what is left.\n\n"
                + "```text\n" + message(batch) + "\n```\n";
    }

    private String attachment(Batch batch, String path) {
        Path file = Paths.get(path);
        return slashes(file.isAbsolute() ? file : batch.root().resolve(file).normalize());
    }

    /** The note as one sentence: trimmed, on one line, starting and ending as a sentence does. */
    private String sentence(String note) {
        String text = note.strip().replaceAll("\\s+", " ");
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text.matches(".*[.!?]$") ? text : text + ".";
    }

    private String slashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private void save(Path file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) {
            return;
        }
        Files.write(file, bytes);
    }
}
