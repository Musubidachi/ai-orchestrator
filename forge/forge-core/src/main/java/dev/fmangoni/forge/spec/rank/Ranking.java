package dev.fmangoni.forge.spec.rank;

import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * A dense, unique ordering over the whole wishlist: projects 1..P, then improvements P+1..P+E.
 *
 * <p>Projects rank above improvements as a matter of policy — finish the things that do not exist
 * before polishing the things that do. It orders what is worked <em>next</em>, not what is
 * possible: an improvement still runs whenever the projects above it are done, parked, or waiting
 * on quota, so ranking below a project is not the same as waiting for it.
 *
 * <p>Contiguous and unique, so every entry has an unambiguous place. That is the trade against
 * sparse priorities: inserting renumbers the tail, which is why renumbering is a command rather
 * than something to do by hand.
 */
@RequiredArgsConstructor
public final class Ranking {

    private final Wishlist wishlist;

    /** The whole wishlist in rank order: projects first, each group by its current rank then id. */
    public List<Spec> ordered() {
        List<Spec> out = new ArrayList<>(wishlist.roots());
        out.addAll(improvements());
        return out;
    }

    /**
     * Every improvement as one flat sequence, in its own rank order.
     *
     * <p>Flat rather than grouped under each parent, so the order is the author's to set outright.
     * Grouping would have made an improvement's position depend on its parent's rank — moving a
     * project would silently reshuffle improvements the author had already ordered.
     *
     * <p>The repository constraint is not lost by flattening: improvements to one project still
     * serialise at run time because they share a working tree. That is a filter on what can run
     * concurrently, not a claim about what should be worked first.
     */
    public List<Spec> improvements() {
        return wishlist.all().stream()
                .filter(Spec::isImprovement)
                .sorted(Wishlist.BY_PRIORITY)
                .toList();
    }

    /** The rank each spec should hold, as a parallel list to {@link #ordered()}. */
    public List<RankAssignment> normalised() {
        List<RankAssignment> out = new ArrayList<>();
        int rank = 0;
        for (Spec spec : ordered()) {
            out.add(new RankAssignment(spec, ++rank));
        }
        return out;
    }

    /**
     * The ordering with one spec moved one place within its own group.
     *
     * <p>Movement is confined to the group because the two are not interchangeable: promoting an
     * improvement above the last project would break the projects-first rule the ranking exists to
     * express, and doing so silently would be worse than refusing.
     *
     * @param delta -1 to move earlier, +1 to move later
     */
    public List<RankAssignment> moved(String id, int delta) {
        boolean improving = wishlist.byId(id).filter(Spec::isImprovement).isPresent();
        List<Spec> group = new ArrayList<>(improving ? improvements() : wishlist.roots());

        int from = indexOf(group, id);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= group.size()) {
            return normalised();
        }
        group.add(to, group.remove(from));

        List<RankAssignment> out = new ArrayList<>();
        int rank = 0;
        for (Spec spec : improving ? wishlist.roots() : group) {
            out.add(new RankAssignment(spec, ++rank));
        }
        for (Spec spec : improving ? group : improvements()) {
            out.add(new RankAssignment(spec, ++rank));
        }
        return out;
    }

    private int indexOf(List<Spec> specs, String id) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Every spec in the order work should be started: projects first, then improvements.
     *
     * <p>The structural rules — a parent must be accepted, one job per repository — filter this
     * list rather than reorder it. They decide what is <em>possible</em>; the ranking decides what
     * is <em>next</em> among those. So an improvement ranked below every project still runs
     * whenever the projects above it are finished, parked or waiting on quota.
     */
    public static List<Spec> runOrder(Wishlist wishlist) {
        return new Ranking(wishlist).ordered();
    }
}
