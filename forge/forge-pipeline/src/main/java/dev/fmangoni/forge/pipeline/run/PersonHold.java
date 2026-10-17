package dev.fmangoni.forge.pipeline.run;

import dev.fmangoni.forge.state.RunState;
import lombok.experimental.UtilityClass;

/**
 * Hands a project to a person when trying again on a timer would only repeat itself.
 *
 * <p>Everything else in the pipeline retries: a quota window reopens, a flaky gate passes on the
 * next go. What this catches is the failure that no amount of waiting fixes — a CLI that is not
 * logged in, a sandbox that will not start a process, a handoff Forge cannot read — and which,
 * retried unattended, costs something every time while changing nothing. Each of those has
 * happened here: nine agent sessions on a broken sandbox, seven refusals from a model the account
 * had no credits for, and three days of a gate re-running its suite before throwing on the same
 * line.
 *
 * <p>The hold is lifted only by an explicit Start, which is the person saying they have looked.
 */
@UtilityClass
public class PersonHold {

    /**
     * Attempts in a row that achieved nothing before the next one becomes a person's decision.
     *
     * <p>More than one, because a single refusal or a single exception is often the weather. Not
     * many more, because each attempt after the second is evidence of nothing new.
     */
    public final int PATIENCE = 3;

    /** Stops the project until someone presses Start, saying why on its card. */
    public void hold(RunState state, String why) {
        state.setNeedsPerson(why);
        state.setLastError("Waiting for you: " + why + ". Press Start once it is dealt with.");
    }

    /**
     * Notes an agent call that failed without doing anything, holding the project at the limit.
     *
     * @return whether the project is now held
     */
    public boolean failedCall(RunState state, String what) {
        state.setFailedCallsInARow(state.getFailedCallsInARow() + 1);
        if (state.getFailedCallsInARow() < PATIENCE) {
            return false;
        }
        hold(state, what + " (" + state.getFailedCallsInARow() + " times in a row)");
        return true;
    }

    /**
     * Notes Forge itself throwing on this project, holding it at the limit.
     *
     * @return whether the project is now held
     */
    public boolean error(RunState state, String what) {
        state.setErrorsInARow(state.getErrorsInARow() + 1);
        if (state.getErrorsInARow() < PATIENCE) {
            return false;
        }
        hold(state, "Forge failed the same way " + state.getErrorsInARow() + " times in a row: "
                + what);
        return true;
    }

    /** An explicit Start: the person has looked, so every count begins again. */
    public void release(RunState state) {
        state.setNeedsPerson(null);
        state.setFailedCallsInARow(0);
        state.setErrorsInARow(0);
    }

    /** Whether anything here would change on {@link #release}. */
    public boolean anythingToRelease(RunState state) {
        return state.getNeedsPerson() != null || state.getFailedCallsInARow() > 0
                || state.getErrorsInARow() > 0;
    }
}
