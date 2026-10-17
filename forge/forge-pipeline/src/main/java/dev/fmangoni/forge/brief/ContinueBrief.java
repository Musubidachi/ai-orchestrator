package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * The prompt for a project that is unfinished rather than broken.
 *
 * <p>Distinct from {@link RepairBrief} because the situations are not alike. A repair says "you
 * thought this worked and it does not"; a continuation says "you said there was more to do, so do
 * it". Handing an agent a failure report for work it already told us was incomplete invites it to
 * chase the symptom — stubbing out the failing check to make the message go away — instead of
 * building the thing that is missing.
 */
@UtilityClass
public class ContinueBrief {

    private final int MAX_OUTPUT = 12_000;

    public String of(CommandResult gateOutput, Plan previous, int session, int maxSessions,
                     Duration cap) {
        return """
                Carry on building. You reported last time that the work was not finished, and the \
                acceptance commands agree — they do not pass yet. That is expected at this stage \
                and is not a failure.

                Keep going through the remaining work. The gate's current output is below as \
                information about where things stand; it is not a defect report, and the right \
                response to most of it is to finish building rather than to patch the symptom.

                Latest acceptance run — `%s`:
                ------------------------------------------------------------
                %s
                ------------------------------------------------------------

                Do not stub, skip or weaken anything to make that output quieter. An acceptance \
                command failing because a feature does not exist yet is the correct state; the same \
                command passing because the check was hollowed out is not recoverable.

                This is session %d of at most %d for this project.

                %s%s""".formatted(
                gateOutput == null ? "(none)" : gateOutput.command(),
                gateOutput == null ? "(no output)" : tail(gateOutput.output()),
                session, maxSessions,
                TimeContract.resuming(previous),
                TimeContract.of(cap));
    }

    private String tail(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= MAX_OUTPUT
                ? output
                : "...[earlier output omitted]...\n" + output.substring(output.length() - MAX_OUTPUT);
    }
}
