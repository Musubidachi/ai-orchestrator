package dev.fmangoni.forge.gate;

import java.time.Duration;

/**
 * The outcome of one acceptance command, as observed by Forge running it.
 *
 * @param command  the command exactly as the spec wrote it
 * @param exitCode process exit status, or -1 if it was killed at its timeout
 * @param output   tail of the combined output, which becomes the repair prompt on failure
 * @param script   the generated script that ran it, kept so a failure can be reproduced by hand
 */
public record CommandResult(
        String command,
        int exitCode,
        String output,
        boolean timedOut,
        Duration elapsed,
        String script) {

    public boolean passed() {
        return exitCode == 0 && !timedOut;
    }

    public String summary() {
        if (timedOut) {
            return "TIMED OUT after " + elapsed.toMinutes() + "m: " + command;
        }
        return (passed() ? "PASS" : "FAIL(" + exitCode + ")") + ": " + command;
    }
}
