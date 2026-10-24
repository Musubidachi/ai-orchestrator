package dev.fmangoni.forge.status.spend;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** The whole machine's token spend: one total, and who moved it. */
@Value
@Builder
public class Spending {

    /**
     * Every project's build-turn tokens, added up.
     *
     * <p>The same figure each project's card already shows, so the total and the board agree by
     * construction. It counts build turns only — reviews, the security read, the planning turn and
     * the design and marketing passes are not on a project's own total and so are not here either.
     */
    private final long total;

    /** The part of {@link #total} that a model is named for. */
    private final long attributed;

    /**
     * The part spent before the split was recorded.
     *
     * <p>Shown as its own figure rather than shared out, because there is nothing left on disk to
     * share it out by. On this machine it is most of the history, and it shrinks on its own as new
     * turns are taken.
     */
    private final long unattributed;

    /** How many projects have spent anything. */
    private final int projects;

    /** Biggest spender first. */
    private final List<ModelSpend> byModel;
}
