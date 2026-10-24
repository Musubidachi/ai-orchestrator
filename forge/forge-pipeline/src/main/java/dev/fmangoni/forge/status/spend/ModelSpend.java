package dev.fmangoni.forge.status.spend;

import lombok.Builder;
import lombok.Value;

/** One model's share of the tokens, summed over every project it worked on. */
@Value
@Builder
public class ModelSpend {

    /** The vendor, as {@code AgentKind} names it. */
    private final String vendor;

    /**
     * The model the vendor was asked for, or a note that it chose for itself.
     *
     * <p>As asked for rather than as answered: no CLI reports which model served a turn, so this is
     * the rung the ladder picked. A vendor that silently substituted another model would be counted
     * here under the one it was asked for, and nothing on this machine could tell.
     */
    private final String model;

    /** Tokens its turns moved, cache reads included, as the vendor reported them. */
    private final long tokens;

    /** How many projects it has taken a turn on. */
    private final int projects;
}
