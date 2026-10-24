package dev.fmangoni.forge.jobs.polish;

import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.SpecWriter;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * An accepted project's new look, written as an enhancement of it rather than a change to it.
 *
 * <p>Accepting is the one decision a person makes about a project, and building a design into it
 * directly would undo that decision behind their back. An enhancement leaves it standing: it is a
 * spec of its own, runs in the project's repository, and must keep every one of the project's
 * acceptance commands passing as well as its own — which here are the same commands, so a restyle
 * that breaks what was accepted is not accepted either.
 */
@UtilityClass
class DesignEnhancement {

    /** Enough for a restyle. One that needs more than this is no longer a restyle. */
    private static final int SESSIONS = 6;

    /** Writes the spec into the wishlist and returns it as loaded back, so what runs is what was written. */
    Spec write(ForgeHome home, Wishlist wishlist, Spec parent, DesignOption chosen, String note) {
        return write(home, wishlist, parent, parent.title() + " visual update", body(parent, chosen, note),
                chosen == null ? "" : chosen.name());
    }

    /**
     * An extra for an accepted project: a pack added beside its look, as an enhancement of its own.
     *
     * @param name    what is added, such as "Pumpkin Patch theme", which titles the enhancement
     * @param request the {@link ExtraBrief} for it
     * @param design  the picked design the pack is drawn from, which its review compares it with
     */
    Spec writeExtra(ForgeHome home, Wishlist wishlist, Spec parent, String name, String request, String design) {
        String body = """
                ## Goal
                Add "%s" to %s, beside the look it has now.

                ## Non-goals
                Any change to what it does, or to the look it has now. Its rules, controls, saved \
                data, ads, default look and every feature stay exactly as they are, nothing is \
                removed, and every acceptance command and recording that passes now must still pass.

                ## Notes
                %s
                """.formatted(name, parent.title(), request);
        return write(home, wishlist, parent, parent.title() + " — " + name, body, design);
    }

    private Spec write(ForgeHome home, Wishlist wishlist, Spec parent, String title, String body, String design) {
        FrontMatter from = parent.front();
        FrontMatter front = new FrontMatter();
        front.setDesign(design == null ? "" : design);
        front.setId(freeId(home, wishlist, parent.id()));
        front.setTitle(title);
        front.setParent(parent.id());
        front.setPriority(from.getPriority());
        front.setAssign(from.getAssign());
        front.setStack(from.getStack());
        front.setClaudeModel(from.getClaudeModel());
        front.setCodexModel(from.getCodexModel());
        front.setAntigravityModel(from.getAntigravityModel());
        front.setAcceptance(List.copyOf(from.getAcceptance()));
        front.setMaxRepairRounds(from.getMaxRepairRounds());
        front.setMaxSessions(SESSIONS);
        front.setCompactAfterSessions(from.getCompactAfterSessions());
        front.setAgentTimeoutMinutes(from.getAgentTimeoutMinutes());
        front.setGateTimeoutMinutes(from.getGateTimeoutMinutes());
        front.setServe(from.getServe());
        front.setServePath(from.getServePath());
        front.setMonetization(from.getMonetization());
        Path file = SpecWriter.write(home.wishlist(), front, body);
        return SpecLoader.load(file);
    }

    private String body(Spec parent, DesignOption chosen, String note) {
        String goal = chosen == null
                ? "Polish how " + parent.title() + " looks, with the look left to you."
                : "Make " + parent.title() + " look like the design picked for it on "
                        + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                        + ": \"" + chosen.name() + "\", drawn by " + chosen.vendor() + ", " + chosen.pitch()
                        + ". The mock-up is in the repository at artifacts/polish-design.html.";
        return """
                ## Goal
                %s

                ## Non-goals
                Any change to what it does. Its rules, controls, saved data, ads and every feature stay \
                exactly as they are, nothing is removed, and every acceptance command and recording \
                that passes now must still pass.

                ## Notes
                %s
                """.formatted(goal, PolishBrief.request(note, chosen));
    }

    /** The first {@code <parent>.<n>} that no spec has and no run directory holds. */
    private String freeId(ForgeHome home, Wishlist wishlist, String parentId) {
        for (int n = 1; ; n++) {
            String id = parentId + "." + n;
            if (wishlist.byId(id).isEmpty() && !Files.exists(home.runs().resolve(id))) {
                return id;
            }
        }
    }
}
