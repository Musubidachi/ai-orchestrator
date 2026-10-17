package dev.fmangoni.forge.pipeline.turn;

import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import lombok.experimental.UtilityClass;

/**
 * The plan from {@link PlanStep}, as a section to put in front of an implementation turn.
 *
 * <p>Read from disk at every turn rather than carried in the run state: it is written once and read
 * many times, a person can edit it between turns, and a turn that follows an edited plan is exactly
 * what editing it is for.
 */
@UtilityClass
public class PlanText {

    /** What a plan longer than this would cost on every later turn is not worth the tail of it. */
    private final int MAX = 20_000;

    /** The plan section, or blank when there is no plan — in which case the turn reads as it always did. */
    public String section(RunPaths paths) {
        if (!Files.isRegularFile(paths.planFile())) {
            return "";
        }
        String plan = read(paths);
        if (plan.isBlank()) {
            return "";
        }
        return """


                ## The plan

                A stronger model planned this project before any code was written. Follow it. If a \
                step turns out to be wrong — the plan assumed something that is not true, or names \
                a file that cannot work — say so in your handoff and do the sensible thing; do not \
                silently do something else.

                %s
                """.formatted(plan.length() <= MAX ? plan : plan.substring(0, MAX) + "\n...[plan truncated]");
    }

    private String read(RunPaths paths) {
        try {
            return Files.readString(paths.planFile(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            System.err.println("could not read the plan: " + e.getMessage());
            return "";
        }
    }
}
