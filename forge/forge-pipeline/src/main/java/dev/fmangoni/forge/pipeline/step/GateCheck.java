package dev.fmangoni.forge.pipeline.step;

import dev.fmangoni.forge.gate.CommandResult;
import java.util.Optional;

/**
 * One check the gate adds after the spec's commands, which has no script of its own: the default
 * look, the owned folders, the words of every look.
 *
 * <p>Asked only of a gate that has passed so far, in the order the {@code Outline} lists them, and
 * the first failure stops the rest: a failing gate already has a reason to give, and a reference
 * adopted from a failing build would hold the project to a look nobody accepted.
 */
@FunctionalInterface
public interface GateCheck {

    /** What failed, reported as a failed command, or nothing when the check is satisfied. */
    Optional<CommandResult> failure();
}
