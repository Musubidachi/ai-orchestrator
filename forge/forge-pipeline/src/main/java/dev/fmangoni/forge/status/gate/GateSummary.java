package dev.fmangoni.forge.status.gate;

import dev.fmangoni.forge.pipeline.step.QuickGate;
import dev.fmangoni.forge.state.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * What the last gate did with each acceptance command, worked out from what the state recorded.
 *
 * <p>The state keeps no list of the commands a gate ran, but it keeps enough to tell. A quick gate
 * names what it left out ({@code notRunAtLastGate}, which every full gate empties); a gate stops at
 * the first command that fails ({@code lastFailure}); and every command that runs adds its time
 * ({@code commandSeconds}). So the commands before a failure passed, the one named failed, the ones
 * after it were never reached, and a command with no time at all came into the spec since.
 *
 * <p>A command's usual time is the middle of its recorded times, the figure {@code QuickGate} goes
 * by, so a command the page draws past the line is one a quick gate leaves out.
 */
@UtilityClass
public class GateSummary {

    public LastGate of(List<String> acceptance, RunState state) {
        Map<String, List<Integer>> times = state.getCommandSeconds() == null ? Map.of() : state.getCommandSeconds();
        List<String> left = state.getNotRunAtLastGate() == null ? List.of() : state.getNotRunAtLastGate();
        String failed = state.getLastFailure() == null ? "" : state.getLastFailure().command();
        List<GateCommand> commands = commands(acceptance, times, left, failed);
        boolean ran = commands.stream().anyMatch(GateCommand::ran) || !failed.isEmpty();
        if (acceptance.isEmpty() || !ran) {
            return LastGate.none();
        }
        // A failure that is none of the commands is one of the checks run after them all passed.
        String check = failed.isEmpty() || acceptance.contains(failed) ? "" : failed;
        long seconds = commands.stream().filter(GateCommand::ran)
                .mapToLong(command -> command.seconds() == null ? 0 : command.seconds()).sum();
        return new LastGate(true, !left.isEmpty(), failed.isEmpty(), check, seconds, QuickGate.SLOW_SECONDS,
                commands);
    }

    private List<GateCommand> commands(List<String> acceptance, Map<String, List<Integer>> times,
                                       List<String> left, String failed) {
        List<GateCommand> commands = new ArrayList<>();
        boolean reached = true;
        for (String command : acceptance) {
            List<Integer> seen = times.getOrDefault(command, List.of());
            // Failed before new: a command can fail on its first run without a time to record.
            String part = left.contains(command) ? GateCommand.WAITS
                    : !reached ? GateCommand.UNREACHED
                    : command.equals(failed) ? GateCommand.FAILED
                    : seen.isEmpty() ? GateCommand.NEW
                    : GateCommand.PASSED;
            reached = reached && !GateCommand.FAILED.equals(part);
            commands.add(new GateCommand(command, part, seen.isEmpty() ? null : seen.getLast(),
                    seen.isEmpty() ? null : usual(seen)));
        }
        return commands;
    }

    /** The middle of a command's times, taken as {@code QuickGate} takes it. */
    private int usual(List<Integer> seen) {
        List<Integer> sorted = new ArrayList<>(seen);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
