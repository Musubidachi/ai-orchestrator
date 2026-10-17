package dev.fmangoni.forge.gate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on a project: did every acceptance command the spec declared actually pass.
 *
 * <p>This is the only thing in Forge entitled to say a project is done. Nothing here derives from
 * what an agent reported about its own work.
 *
 * @param commands each acceptance command in the order the spec listed them
 * @param evidence files collected from the {@code evidence} globs, for the report
 */
public record GateResult(List<CommandResult> commands, List<Path> evidence) {

    /** Every command passed. Commands stop at the first failure, so this means all of them ran. */
    public boolean passed() {
        return !commands.isEmpty() && commands.stream().allMatch(CommandResult::passed);
    }

    public Optional<CommandResult> firstFailure() {
        return commands.stream().filter(c -> !c.passed()).findFirst();
    }

    /** One line per command, for the report and the run log. */
    public String summary() {
        return commands.stream().map(CommandResult::summary).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
}
