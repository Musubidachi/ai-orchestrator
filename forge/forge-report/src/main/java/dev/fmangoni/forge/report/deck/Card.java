package dev.fmangoni.forge.report.deck;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * One thing to rule on, as the quick review shows it: a sentence to decide on, and the rest folded
 * away behind it.
 */
@Value
@Builder
public class Card {

    /** The same id the full form rules on, so a ruling made on either page is the same ruling. */
    private final String id;

    /** {@code security}, {@code blocking}, {@code unproven} or {@code remaining}. */
    private final String kind;

    /** Where it came from, as the form labels it. */
    private final String source;

    /** The item exactly as the form words it. Sent back with the ruling, and quoted to the agent. */
    private final String text;

    /** What is put in front of the person: the reviewer's plain line when there is one. */
    private final String headline;

    /** Everything the headline left out, for whoever opens it. Blank when there is nothing more. */
    private final String detail;

    /** One of the reviewer's stakes words, {@code security} for a security finding, or blank. */
    private final String stakes;

    /** A line saying this was ruled on before and has come back, or blank. */
    private final String cameBack;

    /** How many times the builder has already been sent to fix it. */
    private final int attempts;

    /** Why the reviewer said the last fix did not answer it, or blank. */
    private final String stillPresent;

    /** What the person has already written about it, oldest first. */
    private final List<String> notes;
}
