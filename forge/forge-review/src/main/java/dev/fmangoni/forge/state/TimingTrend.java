package dev.fmangoni.forge.state;






















/**
 * What the latest run of a command cost, and what it usually costs.
 *
 * @param latest      seconds the most recent run took
 * @param usual       the median of the runs before it, or {@code latest} when there are none
 * @param runs        how many times it has been measured
 */
public record TimingTrend(String command, int latest, int usual, int runs) {

    /**
     * How many seconds slower a command must be, as well as proportionally slower, to be reported.
     *
     * <p>A ratio alone means nothing for short commands. This was a 5-second floor, which looked
     * enough in the report and was not once the warnings reached the cards: every project on this
     * machine carried one — {@code mvn -q test} at 6s against a usual 3s, {@code pip install} at
     * 14s against 8s. Process start-up, a cold cache, a scanner waking up. A warning on every card
     * is a warning on none. Thirty seconds keeps what was real on the same day: a Playwright suite
     * at 94s against 51s, another at 93s against 31s.
     */
    private static final long EXTRA_SECONDS = 30;

    /** How much slower than its own history a command must be before it is worth mentioning. */
    private static final double SUSPICIOUS = 1.5;

    /** Slower than its own history by enough, and slow enough, to be worth a person's attention. */
    public boolean slowerThanUsual() {
        return usual > 0 && latest >= usual * SUSPICIOUS && latest - usual >= EXTRA_SECONDS;
    }
}
