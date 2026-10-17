package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.agent.catalog.VendorModels;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * {@code forge models}: what each vendor will actually run, and what the ladder says.
 *
 * <p>The two drift apart, and until now they drifted silently. A subscription does not reach every
 * model its vendor publishes, models are retired, and {@code models.json} is edited by hand — so a
 * ladder can name something that no longer exists, and the first sign of it is a run spending a
 * turn being refused.
 *
 * <p>Plain listing by default, because seeing the difference is usually all anyone wants.
 * {@code --refresh} rewrites the ladder from the vendors, which also asks Antigravity directly and
 * therefore takes a moment.
 */
@UtilityClass
public class ModelsCommand {

    public void run(ForgeHome home, String[] args) {
        boolean refresh = Args.has(args, "--refresh");
        Map<AgentKind, List<String>> available = refresh ? VendorModels.asked() : VendorModels.cached();

        if (refresh) {
            rewrite(home, available);
            return;
        }
        show(ModelTiers.load(home.root()), available);
    }

    private void show(ModelTiers tiers, Map<AgentKind, List<String>> available) {
        for (AgentKind kind : AgentKind.values()) {
            List<String> ladder = tiers.forVendor(kind);
            List<String> offered = available.getOrDefault(kind, List.of());
            System.out.println(kind + "  (cheapest first)");
            System.out.println("  in use:    " + say(ladder));
            System.out.println("  available: " + (offered.isEmpty() ? unknown(kind) : say(offered)));
            unused(ladder, offered);
            System.out.println();
        }
        System.out.println("models.json is yours to edit. 'forge models --refresh' rewrites it"
                + " from the vendors.");
    }

    /**
     * Why a vendor has nothing to show, which is two different situations.
     *
     * <p>A vendor with no cache to read is not asked at all here — asking it means running a
     * command that reaches the network, and this listing is meant to be instant. Saying "could not
     * ask" of one that was never asked reads as a broken CLI, and sends someone looking for a fault
     * that is not there.
     */
    private String unknown(AgentKind kind) {
        return kind == AgentKind.ANTIGRAVITY
                ? "not asked - it has no cache to read, so 'forge models --refresh' asks it directly"
                : "could not ask this vendor; check the CLI is installed and logged in";
    }

    /**
     * The two ways the ladder and the vendor can disagree.
     *
     * <p>Named separately because they mean opposite things: a model on the ladder that the vendor
     * does not offer is a run waiting to fail, while one the vendor offers that the ladder omits is
     * usually deliberate.
     */
    private void unused(List<String> ladder, List<String> offered) {
        if (offered.isEmpty()) {
            return;
        }
        List<String> gone = ladder.stream().filter(model -> !offered.contains(model)).toList();
        List<String> spare = offered.stream().filter(model -> !ladder.contains(model)).toList();
        if (!gone.isEmpty()) {
            System.out.println("  unusable:  " + say(gone) + "  <- this account cannot run these");
        }
        if (!spare.isEmpty()) {
            System.out.println("  not used:  " + say(spare));
        }
    }

    private void rewrite(ForgeHome home, Map<AgentKind, List<String>> available) {
        if (available.isEmpty()) {
            System.out.println("No vendor could be asked, so models.json was left alone."
                    + " Check the CLIs are installed and logged in.");
            return;
        }
        // Over the top of what is there rather than in place of it: a vendor that could not be
        // asked this time must keep its ladder instead of vanishing from the file.
        Map<String, List<String>> written =
                new LinkedHashMap<>(ModelTiers.load(home.root()).byVendor());
        Map<AgentKind, List<String>> placed = VendorModels.forLadder(available);
        placed.forEach((kind, models) -> written.put(kind.name(), models));
        new ModelTiers(written).write(home.root());
        System.out.println("Rewrote " + home.root().resolve("models.json") + ":");
        written.forEach((vendor, models) -> System.out.println("  " + vendor + ": " + say(models)));
        System.out.println();
        available.forEach((kind, models) -> {
            List<String> left = models.stream()
                    .filter(model -> !placed.getOrDefault(kind, List.of()).contains(model)).toList();
            if (!left.isEmpty()) {
                System.out.println(kind + " also offers " + say(left) + ", which Forge cannot rank;"
                        + " add it to models.json where it belongs to use it.");
            }
        });
        System.out.println("A vendor that could not be asked keeps whatever it had.");
    }

    private String say(List<String> models) {
        return models.isEmpty() ? "none" : String.join(", ", models);
    }
}
