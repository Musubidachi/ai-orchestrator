package dev.fmangoni.forge.status.gate;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * Reads a project's last gate: its state, and its spec's commands with its ancestors' included, the
 * list every gate runs.
 *
 * <p>Read fresh on each request, like the rest of the status, so it cannot disagree with the files.
 * The id is looked up in the wishlist first, so only a project that exists is ever read.
 */
@UtilityClass
public class LastGateReader {

    public LastGate read(Path runsDir, Path wishlistDir, String id) {
        Wishlist wishlist = Wishlist.load(wishlistDir);
        return wishlist.byId(id)
                .map(wishlist::effectiveAcceptance)
                .flatMap(acceptance -> RunStore.survey(RunPaths.under(runsDir, id).stateFile())
                        .map(state -> GateSummary.of(acceptance, state)))
                .orElseGet(LastGate::none);
    }
}
