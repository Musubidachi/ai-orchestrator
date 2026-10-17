package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.rank.RankAssignment;
import dev.fmangoni.forge.spec.rank.RankWriter;
import dev.fmangoni.forge.spec.rank.Ranking;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * {@code forge rank}: renumbers the wishlist, projects 1..P, then improvements P+1..P+E.
 *
 * <p>A command rather than something automatic, because it edits the user's spec files. It touches
 * only the {@code priority} line, so comments and formatting survive.
 */
@UtilityClass
public class RankCommand {

    public void run(ForgeHome home) {
        List<RankAssignment> ranking = new Ranking(Wishlist.load(home.wishlist())).normalised();
        int changed = RankWriter.apply(ranking);
        ranking.forEach(a -> System.out.printf("%3d  %-8s %s%s%n",
                a.rank(), a.spec().id(), a.spec().title(),
                a.spec().isImprovement() ? "  (improves " + a.spec().parentId().orElse("?") + ")" : ""));
        System.out.println(changed + " spec file(s) renumbered");
    }
}
