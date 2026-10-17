package dev.fmangoni.forge.review.model;

import java.util.List;
import java.util.Map;

/**
 * What a review has to check besides the change itself: the findings from the round before.
 *
 * @param previous  the blocking findings the agent was last sent to fix, each with its id
 * @param answers   what the agent said it did about each, by finding id
 * @param ruledFine findings a person has already ruled acceptable, which must not be raised again
 * @param files     the files the previous findings are in, as they stand now, by path. The review
 *                  diff is cut short on a large project, and a recheck that could not see the file
 *                  judged a real fix "still present" because "the supplied diff omits" it: 005 was
 *                  held for a fix that was in place and tested, 001 and 002 the same way.
 */
public record ReviewRound(List<Finding> previous, Map<String, String> answers, List<String> ruledFine,
                          Map<String, String> files) {

    /** A first review: nothing to recheck. */
    public static ReviewRound first() {
        return new ReviewRound(List.of(), Map.of(), List.of(), Map.of());
    }

    public boolean rechecking() {
        return !previous.isEmpty();
    }
}
