package dev.fmangoni.forge.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.deck.Card;
import dev.fmangoni.forge.report.deck.Deck;
import dev.fmangoni.forge.report.deck.DeckView;
import dev.fmangoni.forge.report.review.DecisionSection;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.state.GateLooks;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the gate passed but asked a person to look at, in the two places a person rules on a
 * project. Both must ask it on its own: a look that could be accepted in a batch would be a pass
 * nobody looked at, which is what the gate had already decided before the code existed.
 */
class GateLooksInTheReviewTest {

    private static final String UNPROVEN = "the export has never been opened in the real application";

    @TempDir
    Path runs;

    private RunPaths paths;
    private RunState state;

    @BeforeEach
    void setUp() throws IOException {
        paths = RunPaths.under(runs, "001");
        Files.createDirectories(paths.repo());

        Plan plan = new Plan();
        plan.setNotVerified(new ArrayList<>(List.of(UNPROVEN)));

        state = new RunState();
        state.setId("001");
        state.setTitle("The event");
        state.setPhase(Phase.DONE);
        state.setPlan(plan);
        GateLooks.record(state, List.of(new CommandResult("python mockups.py", CommandResult.LOOK,
                "LOOK: the shop's header moved\n", false, Duration.ofSeconds(4), null)));
    }

    @Test
    @DisplayName("the quick review deals a look on its own, not in the pile to skim")
    void answeredAloneInTheDeck() {
        DeckView deck = Deck.of(runs, paths, state, null);

        assertEquals(List.of("`python mockups.py`: LOOK: the shop's header moved"),
                deck.getMustDecide().stream().map(Card::getText).toList());
        assertEquals(List.of(UNPROVEN), deck.getSkim().stream().map(Card::getText).toList());
    }

    @Test
    @DisplayName("the report's form starts a look with nothing ticked, and the rest at Fine as it is")
    void unticked() {
        String form = DecisionSection.render(paths, state, null);
        String lookId = GateLooks.with(List.of(), state).get(0).id();
        String unprovenId = ReviewItem.of("unproven", ReviewItem.NOT_VERIFIED, UNPROVEN).id();

        assertTrue(form.contains(GateLooks.SOURCE), "labelled as the gate's question");
        assertFalse(form.contains("name=\"v-" + lookId + "\" value=\"ok\" checked"), "a look is never pre-ticked");
        assertTrue(form.contains("name=\"v-" + unprovenId + "\" value=\"ok\" checked"), "the rest is unchanged");
    }

    @Test
    @DisplayName("a project whose gate asked nothing has nothing extra to rule on")
    void nothingAsked() {
        GateLooks.record(state, List.of());

        assertTrue(Deck.of(runs, paths, state, null).getMustDecide().isEmpty());
    }
}
