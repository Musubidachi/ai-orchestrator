package dev.fmangoni.forge.status.league;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.review.model.Recheck;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import java.util.Locale;

/**
 * One vendor's running count while the runs are read, and the scoring.
 *
 * <p>The scoring lives here, in one place, with nothing clever in it:
 * <ul>
 *   <li>building: 3 for a project that passed the gate, 2 more when a person accepted it, 1 more
 *       when it needed no repair round, and minus 2 for one that parked;</li>
 *   <li>reviewing: 1 for each finding the builder then fixed, minus 1 for each one withdrawn.</li>
 * </ul>
 *
 * <p>A finding that is still open scores nothing either way: nobody yet knows whether it was right.
 * A project still being worked on scores nothing either: it has not been judged.
 */
final class Tally {

    static final int FINISHED = 3;
    static final int ACCEPTED = 2;
    static final int CLEAN_GATE = 1;
    static final int PARKED = -2;
    static final int FINDING_FIXED = 1;
    static final int FINDING_WITHDRAWN = -1;

    private int built;
    private int finished;
    private int accepted;
    private int parked;
    private int cleanGates;
    private int repairRounds;
    private int finishedSessions;
    private long finishedTokens;
    private int reviewed;
    private int findingsFixed;
    private int findingsWithdrawn;
    private int findingsOpen;

    /** Counts a project this vendor was assigned to build. */
    void built(RunState state) {
        built++;
        int needed = repairsNeeded(state);
        repairRounds += needed;
        Phase phase = state.getPhase();
        if (phase == Phase.PARKED) {
            parked++;
        }
        if (phase != Phase.DONE && phase != Phase.ACCEPTED) {
            return;
        }
        finished++;
        finishedSessions += state.getSessions();
        finishedTokens += state.getTokensSpent();
        if (phase == Phase.ACCEPTED) {
            accepted++;
        }
        if (needed == 0) {
            cleanGates++;
        }
    }

    /**
     * Every repair round the project has needed, not only those since it was last reopened.
     *
     * <p>The larger of the two counters, because a project finished before the lifetime one existed
     * has only the budget to go on.
     */
    private static int repairsNeeded(RunState state) {
        return Math.max(state.getRepairRounds(), state.getRepairRoundsEver());
    }

    /** Counts a project whose latest review this vendor wrote. */
    void reviewed(Review review) {
        reviewed++;
        findingsOpen += review.blocking().size();
        for (Recheck recheck : review.getRechecks()) {
            String said = recheck.getStatus() == null ? "" : recheck.getStatus().strip().toLowerCase(Locale.ROOT);
            if (said.equals("fixed")) {
                findingsFixed++;
            } else if (said.equals("withdrawn")) {
                findingsWithdrawn++;
            }
        }
    }

    Standing standing(String vendor) {
        int buildPoints = finished * FINISHED + accepted * ACCEPTED + cleanGates * CLEAN_GATE + parked * PARKED;
        int reviewPoints = findingsFixed * FINDING_FIXED + findingsWithdrawn * FINDING_WITHDRAWN;
        return Standing.builder()
                .vendor(vendor)
                .points(buildPoints + reviewPoints)
                .buildPoints(buildPoints)
                .reviewPoints(reviewPoints)
                .built(built)
                .finished(finished)
                .accepted(accepted)
                .parked(parked)
                .cleanGates(cleanGates)
                .repairRounds(repairRounds)
                .finishedSessions(finishedSessions)
                .finishedTokens(finishedTokens)
                .reviewed(reviewed)
                .findingsFixed(findingsFixed)
                .findingsWithdrawn(findingsWithdrawn)
                .findingsOpen(findingsOpen)
                .build();
    }
}
