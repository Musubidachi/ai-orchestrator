package dev.fmangoni.forge.pictures.view;

import dev.fmangoni.forge.pictures.model.JobState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One picture batch, as the dashboard and the review show it.
 *
 * @param id         the batch folder's name under {@code runs/}
 * @param title      what to call it
 * @param target     the project the pictures are for, or null
 * @param agent      who draws
 * @param rules      a link to what each picture is checked against, or null
 * @param counts     how many jobs are in each state, every state named
 * @param jobs       every job, in list order
 * @param message    the message to send the agent that draws
 * @param promptFile where that message is kept, in the folder the agent works in
 * @param placeable  how many kept pictures a click would place now
 * @param problem    why the batch cannot be read, or null; then there are no jobs
 */
public record BatchView(String id, String title, String target, String agent, String rules,
                        Map<JobState, Integer> counts, List<JobView> jobs, String message, String promptFile,
                        int placeable, String problem) {

    public BatchView {
        // In the states' order, which is the order a job goes through them.
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        jobs = List.copyOf(jobs);
    }

    /** A batch whose folder cannot be read, saying why. */
    public static BatchView broken(String id, String problem) {
        Map<JobState, Integer> none = new EnumMap<>(JobState.class);
        for (JobState state : JobState.values()) {
            none.put(state, 0);
        }
        return new BatchView(id, id, null, null, null, none, List.of(), null, null, 0, problem);
    }
}
