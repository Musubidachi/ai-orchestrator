package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.Optional;

/** Finds a project by its spec id, reading the wishlist as it is on disk now. */
public final class Projects {

    private final ForgeHome home;
    private final Eligibility rules;

    public Projects(ForgeHome home, Eligibility rules) {
        this.home = home;
        this.rules = rules;
    }

    public Wishlist wishlist() {
        return Wishlist.load(home.wishlist());
    }

    /** Where the wishlist and the runs live, for a job that writes a new spec. */
    public ForgeHome home() {
        return home;
    }

    public Optional<Project> find(String id) {
        Wishlist wishlist = wishlist();
        return wishlist.byId(id).map(spec -> of(spec, wishlist));
    }

    public Project of(Spec spec, Wishlist wishlist) {
        return new Project(spec, wishlist, rules.pathsFor(spec, wishlist));
    }

    public static String unknown(String id) {
        return "no spec with id " + id;
    }
}
