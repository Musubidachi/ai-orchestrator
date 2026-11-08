package dev.fmangoni.forge.status.gate;

/**
 * One acceptance command, as the last gate left it.
 *
 * @param command the command as the spec writes it
 * @param state   {@link #PASSED} or {@link #FAILED} when the last gate ran it; {@link #UNREACHED} when
 *                it comes after the one that failed; {@link #WAITS} when a quick gate left it for the
 *                full one; {@link #NEW} when it has never been timed, as a command the spec gained
 *                since the last gate has not
 * @param seconds what it took the last time it ran, at this gate or an earlier one; null if never
 * @param usual   the middle of its recorded times, the figure a quick gate goes by; null if never
 */
public record GateCommand(String command, String state, Integer seconds, Integer usual) {

    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String UNREACHED = "unreached";
    public static final String WAITS = "waits";
    public static final String NEW = "new";

    /** Whether the last gate ran it: it then has a time from that gate. */
    boolean ran() {
        return PASSED.equals(state) || FAILED.equals(state);
    }
}
