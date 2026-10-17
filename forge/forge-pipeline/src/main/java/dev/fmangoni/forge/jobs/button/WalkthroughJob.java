package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.walkthrough.Speech;
import dev.fmangoni.forge.walkthrough.Walkthrough;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Says what a project is, out loud.
 *
 * <p>The words are read off the run's own files rather than asked of a model, so this costs no
 * quota, works with every vendor rate-limited, and cannot describe a project as anything other than
 * what is on disk. See {@link Walkthrough}.
 *
 * <p>The transcript is written first and the recording second, on a worker: the page has the words
 * immediately and reads them in the browser's own voice, while the {@code .wav} is for listening to
 * later, away from the machine. A failure to record loses the audio and nothing else.
 */
public final class WalkthroughJob {

    private final Projects projects;
    private final JobPool pool;

    public WalkthroughJob(Projects projects, JobPool pool) {
        this.projects = projects;
        this.pool = pool;
    }

    public Narration speak(String id) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Narration.problem(Projects.unknown(id));
        }
        RunPaths paths = found.get().paths();
        RunState state = RunStore.survey(paths.stateFile()).orElse(null);
        if (state == null) {
            return Narration.problem(found.get().spec().title() + " has not run yet, so there is "
                    + "nothing to say about it.");
        }
        String text = Walkthrough.of(paths, state, ReviewStore.load(paths.reviewFile()));
        write(paths, text);
        record(id, paths, text);
        return new Narration(text, "runs/" + id + "/" + paths.walkthroughAudio().getFileName());
    }

    /** The transcript, written where the report and the state file already are. */
    private void write(RunPaths paths, String text) {
        try {
            Files.writeString(paths.walkthroughFile(), text + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("could not write " + paths.walkthroughFile() + ": " + e.getMessage());
        }
    }

    /**
     * Records the walkthrough on a worker.
     *
     * <p>On a worker because speech is a subprocess of a few seconds, and because that is how every
     * other job here holds a slot: while it runs, the project shows as busy and nothing else starts
     * on it.
     */
    private void record(String id, RunPaths paths, String text) {
        ButtonJob.submit(pool, id, "walkthrough", paths, () -> {
            if (!Speech.writeWav(text, paths.walkthroughAudio())) {
                System.out.println("[" + id + "] the walkthrough was written but could not be recorded");
            }
        });
    }
}
