package dev.fmangoni.forge.state;

import dev.fmangoni.forge.gate.CommandResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * How long each acceptance command has taken, run after run.
 *
 * <p>Every gate already measures this and every gate used to throw it away. Keeping it turns a
 * number Forge was computing anyway into the one performance signal that costs nothing and cannot
 * be flaky: not "is this fast enough", which no honest threshold can answer on a laptop that is
 * also running a browser, but "is this slower than it was", which the same machine can answer about
 * itself.
 *
 * <p>Deliberately not a gate. A wall-clock assertion in the acceptance commands would fail on a
 * busy machine and park a healthy project, and parking for the weather is worse than not measuring.
 * A slowdown is reported for a person to look at, and it is the person who decides whether it
 * matters.
 */
@UtilityClass
public class Timings {

    /** Enough history to have a stable middle, few enough that a real change shows through. */
    private final int KEPT = 10;

    /** Adds this run's durations, oldest entries falling off the front. */
    public void record(RunState state, List<CommandResult> results) {
        Map<String, List<Integer>> history = state.getCommandSeconds() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(state.getCommandSeconds());

        for (CommandResult result : results) {
            if (result.elapsed() == null) {
                continue;
            }
            List<Integer> seen = new ArrayList<>(
                    history.getOrDefault(result.command(), List.of()));
            seen.add((int) result.elapsed().toSeconds());
            while (seen.size() > KEPT) {
                seen.removeFirst();
            }
            history.put(result.command(), seen);
        }
        state.setCommandSeconds(history);
    }

    /** One trend per command, in the order the spec lists them. */
    public List<TimingTrend> of(RunState state) {
        Map<String, List<Integer>> history = state.getCommandSeconds();
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<TimingTrend> trends = new ArrayList<>();
        history.forEach((command, seen) -> {
            if (seen.isEmpty()) {
                return;
            }
            int latest = seen.getLast();
            // The median of everything before this run: one slow run should not redefine "usual",
            // and a mean would let it.
            List<Integer> earlier = new ArrayList<>(seen.subList(0, seen.size() - 1));
            trends.add(new TimingTrend(command, latest,
                    earlier.isEmpty() ? latest : median(earlier), seen.size()));
        });
        return trends;
    }

    private int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
