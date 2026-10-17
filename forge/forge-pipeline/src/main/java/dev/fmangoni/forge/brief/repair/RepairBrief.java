package dev.fmangoni.forge.brief.repair;

import dev.fmangoni.forge.brief.TimeContract;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * The prompt after a failed gate: here is the command that failed and exactly what it printed.
 *
 * <p>The agent is given the observed failure rather than a summary of it. A paraphrased error is
 * the orchestrator guessing at the cause, and the orchestrator has no idea what the cause is —
 * that is the agent's job, and it needs the raw output to do it.
 */
@UtilityClass
public class RepairBrief {

    /** The gate output can be enormous; the tail holds the failure and its stack trace. */
    private final int MAX_OUTPUT = 24_000;

    public String of(CommandResult failure, int roundsUsed, int roundsAllowed,
                     Duration cap, Plan previous) {
        return """
                The acceptance gate failed. Fix the cause.

                Failing command:
                    %s

                %s

                Output:
                ------------------------------------------------------------
                %s
                ------------------------------------------------------------

                Fix the underlying problem in the code. Do not edit the acceptance command, delete \
                the failing test, or weaken its assertions — the gate re-runs the same command \
                afterwards, and a project that passes only because its checks were hollowed out is \
                worse than one that honestly fails.

                This is repair round %d of %d. If the remaining rounds run out the project is \
                parked for a human, so if the spec itself is the problem, say so plainly instead \
                of guessing.

                %s%s""".formatted(failure.command(), status(failure), tail(failure.output()),
                roundsUsed, roundsAllowed,
                TimeContract.resuming(previous),
                TimeContract.of(cap));
    }

    private String status(CommandResult failure) {
        return failure.timedOut()
                ? "It was killed after " + failure.elapsed().toMinutes()
                        + " minutes without finishing. Suspect a hang, a prompt waiting for input, "
                        + "or an unbounded loop."
                : "It exited with status " + failure.exitCode() + ".";
    }

    private String tail(String output) {
        if (output.length() <= MAX_OUTPUT) {
            return output;
        }
        return "...[earlier output omitted]...\n" + output.substring(output.length() - MAX_OUTPUT);
    }
}
