package dev.fmangoni.forge.status.league;

import lombok.Builder;
import lombok.Value;

/**
 * One vendor's line in the league: what it built, what it reviewed, and the points that came to.
 *
 * <p>The counts are sent as well as the points, because the points are a way of ordering the table
 * and the counts are what actually happened. With six projects on the board a single parked one
 * moves a vendor two places, and the row should let that be seen for what it is.
 */
@Value
@Builder
public class Standing {

    private final String vendor;

    private final int points;

    private final int buildPoints;

    private final int reviewPoints;

    /** Projects assigned to this vendor that have run at all. */
    private final int built;

    /** Of those, how many passed the gate: DONE or ACCEPTED. */
    private final int finished;

    /** Of those, how many a person has accepted. */
    private final int accepted;

    private final int parked;

    /** Finished without a single repair round: the gate passed on the work as first handed in. */
    private final int cleanGates;

    /** Repair rounds across everything it built. */
    private final int repairRounds;

    /** Sessions and tokens across the finished projects only, for a per-project average. */
    private final int finishedSessions;

    private final long finishedTokens;

    /** Projects whose latest review this vendor wrote. */
    private final int reviewed;

    /** Findings it raised that the builder then fixed, by its own recheck. */
    private final int findingsFixed;

    /** Findings it raised and later withdrew. */
    private final int findingsWithdrawn;

    /** Blocking findings it raised that are still open. */
    private final int findingsOpen;
}
