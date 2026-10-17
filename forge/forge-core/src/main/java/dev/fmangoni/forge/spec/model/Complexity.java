package dev.fmangoni.forge.spec.model;

import java.util.Locale;

/**
 * How demanding a project is, as the drafting agent judged it.
 *
 * <p>Deliberately not "which model should build this". A model asked to rank other models is
 * guessing: the tiers on this machine are newer than most training data, their relative strength on
 * this kind of work is not something it can know, and it can see neither the quota left nor what a
 * tier costs the person paying. What it <em>can</em> read off a spec is whether the work is a
 * afternoon's scripting or a research problem — so it judges that, and the ordering in
 * {@code models.json} decides what that maps to.
 */
public enum Complexity {

    /** Well-trodden work with a clear shape: a CLI, a parser, a report. */
    ROUTINE(0.25),

    /** Several moving parts, or a domain with real edge cases. */
    INVOLVED(0.6),

    /** Genuinely hard: unclear approach, heavy algorithms, or a research-shaped problem. */
    HARD(1.0);

    /** Where this band sits on a ladder, from 0 (smallest) to 1 (largest). */
    private final double position;

    public double position() {
        return position;
    }

    Complexity(double position) {
        this.position = position;
    }

    /** Unrecognised values fall to the middle rather than to an extreme. */
    public static Complexity parse(String value) {
        if (value == null) {
            return INVOLVED;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INVOLVED;
        }
    }
}
