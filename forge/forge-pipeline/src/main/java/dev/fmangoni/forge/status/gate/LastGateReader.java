package dev.fmangoni.forge.status.gate;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Path;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Reads a project's last gate: its state, and its spec's commands with its ancestors' included, the
 * list every gate runs; then the gates noted before it ({@link GateHistory}).
 *
 * <p>Read fresh on each request, like the rest of the status, so it cannot disagree with the files.
 * The id is looked up in the wishlist first, so only a project that exists is ever read.
 */
@UtilityClass
public class LastGateReader {

    public LastGate read(Path runsDir, Path wishlistDir, String id) {
        Wishlist wishlist = Wishlist.load(wishlistDir);
        RunPaths paths = RunPaths.under(runsDir, id);
        return wishlist.byId(id)
                .map(wishlist::effectiveAcceptance)
                .flatMap(acceptance -> RunStore.survey(paths.stateFile())
                        .map(state -> withHistory(GateSummary.of(acceptance, state), state, paths.root())))
                .orElseGet(LastGate::none);
    }

    /**
     * The gate with the ones noted before it, and the session it followed: the last one noted. A
     * version whose gates ran before they were noted has none, and then it is the sessions so far,
     * since every session ends in a gate; unless that gate is running now.
     */
    private LastGate withHistory(LastGate gate, RunState state, Path runRoot) {
        if (!gate.ran()) {
            return gate;
        }
        List<PastGate> history = GateHistory.load(runRoot);
        int after = !history.isEmpty() ? history.getLast().session()
                : state.getPhase() == Phase.GATING ? 0 : state.getSessions();
        return gate.with(after, history);
    }
}
