package dev.fmangoni.forge.agent.catalog;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * What each vendor says this account can actually use.
 *
 * <p>Asked of the tools themselves rather than remembered here, because the answer is a property of
 * a subscription and not of Forge. A ChatGPT plan does not reach every model OpenAI publishes, and
 * a hand-written list goes stale the first time one is retired — which costs a run its turn to
 * discover.
 *
 * <p>Two speeds, and the difference matters. {@link #cached()} reads files the CLIs already keep
 * and is cheap enough to call on a page load; {@link #asked()} also runs {@code agy models}, which
 * reaches the network, and belongs only where a ladder is being written.
 */
@UtilityClass
public class VendorModels {

    /** From the caches the CLIs keep. No processes, no network; safe anywhere. */
    public Map<AgentKind, List<String>> cached() {
        Map<AgentKind, List<String>> found = new LinkedHashMap<>();
        put(found, AgentKind.CLAUDE, ClaudeCatalog.available());
        put(found, AgentKind.CODEX, CodexCatalog.available());
        return found;
    }

    /** Everything {@link #cached()} finds, plus the vendors that have to be asked directly. */
    public Map<AgentKind, List<String>> asked() {
        Map<AgentKind, List<String>> found = cached();
        put(found, AgentKind.ANTIGRAVITY, AntigravityCatalog.available());
        return found;
    }

    /**
     * What discovery may put on a ladder: what each vendor offers, less what cannot be ranked.
     *
     * <p>Different from what a vendor offers, which is what deciding whether a configured model
     * still exists needs. Only Claude's catalog carries names this code cannot place; see
     * {@link ClaudeCatalog#placeable}.
     */
    public Map<AgentKind, List<String>> forLadder(Map<AgentKind, List<String>> offered) {
        Map<AgentKind, List<String>> ladders = new LinkedHashMap<>(offered);
        ladders.computeIfPresent(AgentKind.CLAUDE, (kind, models) -> ClaudeCatalog.placeable(models));
        ladders.values().removeIf(List::isEmpty);
        return ladders;
    }

    /**
     * A vendor that answers with nothing is left out rather than recorded as having no models.
     *
     * <p>The two are very different: one is "not installed, or not logged in", the other is "this
     * account has nothing to run", and writing the second when the first is true would empty a
     * working vendor's ladder.
     */
    private void put(Map<AgentKind, List<String>> found, AgentKind kind, List<String> models) {
        if (!models.isEmpty()) {
            found.put(kind, models);
        }
    }
}
