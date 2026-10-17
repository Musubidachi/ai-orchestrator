package dev.fmangoni.forge.review.feature;

/**
 * What the reviewer's cheapest model said about a polish: whether it changed only how things look.
 *
 * @param reached        whether the model answered at all. Unreached is not a verdict either way,
 *                       and the full review decides
 * @param appearanceOnly whether it read the change as appearance and nothing else
 * @param why            one sentence: what changed beyond appearance, or why there was no answer
 */
public record ScopeVerdict(boolean reached, boolean appearanceOnly, String why) {

    public static ScopeVerdict unreached(String why) {
        return new ScopeVerdict(false, false, why);
    }
}
