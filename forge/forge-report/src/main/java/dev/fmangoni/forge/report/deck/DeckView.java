package dev.fmangoni.forge.report.deck;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A project's whole review, dealt in the order a person should meet it.
 *
 * <p>Three piles. {@code mustDecide} is what cannot be waved through: security findings, blocking
 * findings, and anything ruled on before that has come back. Each is answered on its own, with no
 * answer chosen in advance. {@code features} are the ones nobody has ruled on. {@code skim} is the
 * rest — what the gate does not prove, and work the agent says remains — which starts as fine and
 * is shown together, so the dozen that need no thought do not stand between a person and the two
 * that do.
 */
@Value
@Builder
public class DeckView {

    private final String id;

    private final String title;

    private final String phase;

    /** Why a submission cannot be acted on yet, or blank. The same words the full form uses. */
    private final String notice;

    private final List<Card> mustDecide;

    private final List<FeatureCard> features;

    private final List<Card> skim;

    /** Features already ruled on, which the quick review does not ask about again. */
    private final int featuresAlreadyRuled;

    /**
     * Suggestions from the agent that nobody has answered. Counted, not dealt: saying yes to one is
     * asking for it to be built, which is a bigger decision than a card should make look.
     */
    private final int ideasWaiting;

    /** The full report, where everything here can also be read and ruled on. */
    private final String report;
}
