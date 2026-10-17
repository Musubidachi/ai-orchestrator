package dev.fmangoni.forge.jobs.stop;

import java.util.List;

/**
 * @param settled      whether it is now safe to stop the process
 * @param stillWorking the projects with an agent turn in flight, when it is not
 */
public record Quiesce(boolean settled, List<String> stillWorking) {

    public String describe() {
        return settled
                ? "no agent turn is in flight"
                : "still mid-turn: " + String.join(", ", stillWorking);
    }
}
