package dev.fmangoni.forge.spec.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.model.Complexity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeping a hand-written ladder honest against what a subscription can actually reach.
 *
 * <p>The availability maps here are supplied rather than discovered, because the real ones come
 * from whichever CLIs are installed on the machine running the tests.
 */
class ModelTiersTest {

    @Test
    @DisplayName("a model the vendor has retired is dropped and the rest of the ladder kept")
    void dropsRetiredModels() {
        Map<String, List<String>> configured = Map.of(
                "ANTIGRAVITY", List.of("gemini-3.1-pro-high", "gemini-3.8-flash-low", "gemini-3.8-flash-high"));
        Map<AgentKind, List<String>> available = Map.of(
                AgentKind.ANTIGRAVITY, List.of("gemini-3.8-flash-low", "gemini-3.8-flash-high"));

        assertEquals(List.of("gemini-3.8-flash-low", "gemini-3.8-flash-high"),
                ModelTiers.pruned(configured, available).get("ANTIGRAVITY"));
    }

    @Test
    @DisplayName("a ladder where nothing survives becomes what the vendor does offer")
    void replacesALadderThatIsEntirelyGone() {
        Map<String, List<String>> configured = Map.of(
                "ANTIGRAVITY", List.of("gemini-3.1-pro-low", "gemini-3.1-pro-high"));
        Map<AgentKind, List<String>> available = Map.of(
                AgentKind.ANTIGRAVITY, List.of("gemini-3.8-flash-low", "gemini-3.8-flash-high"));

        // An empty ladder would hand every job back to the CLI's own default and quietly undo the
        // tiering, so the vendor's own list is used instead of nothing.
        assertEquals(List.of("gemini-3.8-flash-low", "gemini-3.8-flash-high"),
                ModelTiers.pruned(configured, available).get("ANTIGRAVITY"));
    }

    @Test
    @DisplayName("a vendor that was not asked keeps the ladder it has")
    void leavesUnaskedVendorsAlone() {
        Map<String, List<String>> configured = Map.of(
                "CLAUDE", List.of("haiku", "sonnet", "opus"),
                "ANTIGRAVITY", List.of("gemini-3.1-pro-high"));
        Map<AgentKind, List<String>> available = Map.of(AgentKind.CLAUDE, List.of("haiku", "sonnet", "opus"));

        // Absent from the availability map means "could not ask", not "offers nothing". Pruning on
        // silence would empty the ladder of a vendor that is merely not installed on this machine.
        Map<String, List<String>> kept = ModelTiers.pruned(configured, available);
        assertEquals(List.of("gemini-3.1-pro-high"), kept.get("ANTIGRAVITY"));
        assertEquals(List.of("haiku", "sonnet", "opus"), kept.get("CLAUDE"));
    }

    @Test
    @DisplayName("the complexity bands land on the pruned ladder, not the one that was written")
    void bandsFollowThePrunedLadder() {
        Map<String, List<String>> configured = Map.of(
                "ANTIGRAVITY", List.of("gemini-3.1-pro-low", "gemini-3.8-flash-low",
                        "gemini-3.8-flash-medium", "gemini-3.8-flash-high"));
        Map<AgentKind, List<String>> available = Map.of(AgentKind.ANTIGRAVITY,
                List.of("gemini-3.8-flash-low", "gemini-3.8-flash-medium", "gemini-3.8-flash-high"));
        ModelTiers tiers = new ModelTiers(ModelTiers.pruned(configured, available));

        // The top band is what a review asks for, and it is what 005 was refused on.
        assertEquals("gemini-3.8-flash-high", tiers.at(Complexity.HARD, AgentKind.ANTIGRAVITY));
    }
}
