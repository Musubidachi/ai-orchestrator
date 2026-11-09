package dev.fmangoni.forge.cli.support;

import dev.fmangoni.forge.jobs.work.Eligibility;
import dev.fmangoni.forge.jobs.work.ReportRefresh;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Every project's report written again, each the way the pipeline writes one after a job.
 *
 * <p>The report command used to write each page itself, from the run's own folder. That wrote a
 * shelved project's page into a folder whose repository had gone, and read every enhancement's page
 * from a repository it does not have, since an enhancement works in its family's: its changes, its
 * pictures and its approach went missing from the page. So each page is written from the paths the
 * pipeline uses, through the pipeline's own refresh, and a project not on this machine is left as it
 * is, with the reason.
 */
@UtilityClass
public class EveryReport {

    /** Writes the pages, and returns each project left alone with why, in the order given. */
    public Map<String, String> write(ForgeHome home, List<RunState> states) {
        Wishlist wishlist = Wishlist.load(home.wishlist());
        Eligibility rules = new Eligibility(home.runs());
        Map<String, String> leftAlone = new LinkedHashMap<>();
        for (RunState state : states) {
            Optional<Spec> spec = wishlist.byId(state.getId());
            String notHere = spec.map(found -> Eligibility.notHere(home.runs(), found)).orElse(null);
            if (notHere != null) {
                leftAlone.put(state.getId(), notHere);
                continue;
            }
            // A run whose spec has left the wishlist is written from its own folder, as it always was;
            // the refresh still leaves it alone when that folder's repository has gone.
            RunPaths paths = spec.map(found -> rules.pathsFor(found, wishlist))
                    .orElseGet(() -> RunPaths.under(home.runs(), state.getId()));
            ReportRefresh.write(paths, state);
        }
        return leftAlone;
    }
}
