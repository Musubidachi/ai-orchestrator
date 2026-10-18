package dev.fmangoni.forge.spec.tier;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.catalog.VendorModels;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.spec.model.Complexity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The models each vendor offers, in the order a slider should present them.
 *
 * <p>Read from {@code models.json} at the Forge root and written there on first use, because the
 * ordering is a judgement rather than a fact. Smaller-to-larger is defensible at the ends and
 * genuinely uncertain in the middle — nothing tells us whether one mid-tier model outranks another
 * for this kind of work — so the order is configuration to be corrected, not a constant to be
 * trusted.
 *
 * <p>Editing the file also handles the case this code cannot: a vendor releasing a model after this
 * was written.
 */
public record ModelTiers(Map<String, List<String>> byVendor) {

    private static final ObjectMapper JSON = Json.mapper();

    /**
     * The fallback ladder, used only where a vendor's CLI cannot be asked.
     *
     * <p>Everything here is a guess about somebody else's subscription, which is why it is the last
     * resort rather than the starting point: what an account can actually reach is read from the
     * CLIs by {@link VendorModels}, and this is what is left when they are not installed, not
     * logged in, or have never been run.
     */
    private static final Map<String, List<String>> DEFAULTS = Map.of(
            "CLAUDE", List.of("haiku", "sonnet", "opus"),
            "CODEX", List.of("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-6-astra"),
            // Read from `agy models` on this machine rather than remembered. Cheapest first, which
            // is what the tier slider means.
            //
            // Google's models only, though agy also fronts Anthropic's and OpenAI's. Picking one of
            // those to review a project the same vendor built would quietly undo the cross-review:
            // the whole value of a second opinion is that it does not share the first one's blind
            // spots. Anyone who wants them can add them to models.json.
            "ANTIGRAVITY", List.of("gemini-3.6-flash-low", "gemini-3.7-flash-medium",
                    "gemini-3.8-flash-medium", "gemini-3.8-flash-high",
                    "gemini-3.1-pro-low", "gemini-3.1-pro-high"));

    public static ModelTiers load(Path forgeRoot) {
        Path file = forgeRoot.resolve("models.json");
        if (!Files.isRegularFile(file)) {
            ModelTiers discovered = new ModelTiers(ordered(discover()));
            discovered.save(file);
            return discovered;
        }
        try {
            // Discovery first, then the file over the top. A models.json written before a vendor
            // existed must not leave that vendor with no models at all — which showed up as a
            // dead slider in the editor and an empty list on /api/models, with nothing to say why.
            Map<String, List<String>> onDisk = JSON.readValue(JsonFile.read(file), Map.class);
            Map<String, List<String>> merged = new LinkedHashMap<>(discover());
            merged.putAll(onDisk);

            ModelTiers tiers = new ModelTiers(ordered(pruned(merged, VendorModels.cached())));
            if (!onDisk.keySet().containsAll(merged.keySet())) {
                // Written back so a vendor added by an upgrade is visible and editable rather than
                // only existing in memory.
                tiers.save(file);
            }
            return tiers;
        } catch (Exception e) {
            // A hand-edited file with a typo should not stop the run; discovery still works.
            System.err.println("Ignoring unreadable models.json: " + e.getMessage());
            return new ModelTiers(ordered(discover()));
        }
    }

    /**
     * The ladder brought in line with what every vendor offers now, Antigravity included.
     *
     * <p>Meant for startup, and nowhere near a page load: this is the one path that runs
     * {@code agy models}, which reaches the network. {@link #load} cannot, so it prunes against
     * the cached vendors only — and Antigravity is precisely the vendor with no cache, so its
     * retired models survived every read and were discovered by a run being refused instead. That
     * cost project 005 a day parked on {@code gemini-3.1-pro-high}, withdrawn weeks earlier.
     *
     * <p>Written back when it changed, so the editor and the next command see the same ladder.
     */
    public static ModelTiers refresh(Path forgeRoot) {
        ModelTiers current = load(forgeRoot);
        Map<AgentKind, List<String>> available = VendorModels.asked();
        if (available.isEmpty()) {
            // No vendor could be asked. Keeping a stale ladder beats emptying a working one.
            return current;
        }
        ModelTiers refreshed = new ModelTiers(ordered(pruned(current.byVendor(), available)));
        if (!refreshed.byVendor().equals(current.byVendor())) {
            refreshed.save(forgeRoot.resolve("models.json"));
        }
        return refreshed;
    }

    /**
     * What the vendors say this account can run, falling back to the guesses above.
     *
     * <p>Only the cached side of discovery: this is reached from {@code /api/models}, which the
     * editor asks on every page load, and a network call there would be felt.
     */
    private static Map<String, List<String>> discover() {
        Map<String, List<String>> found = new LinkedHashMap<>(DEFAULTS);
        VendorModels.forLadder(VendorModels.cached())
                .forEach((kind, models) -> found.put(kind.name(), models));
        return found;
    }

    /**
     * Configured models the vendor no longer offers, dropped.
     *
     * <p>A ladder is edited by hand and a subscription changes underneath it, so a model that was
     * real when it was written is not necessarily real now. Choosing one costs a whole turn to find
     * out, and the refusal arrives as a failed run rather than as an explanation.
     *
     * <p>Only where the vendor actually answered: a vendor absent from {@code available} was not
     * asked, or could not be, and pruning a working ladder on the strength of silence would be
     * worse than the stale entry it was meant to catch.
     */
    static Map<String, List<String>> pruned(Map<String, List<String>> configured,
                                            Map<AgentKind, List<String>> available) {
        Map<AgentKind, List<String>> placeable = VendorModels.forLadder(available);
        Map<String, List<String>> kept = new LinkedHashMap<>(configured);
        available.forEach((kind, offered) -> {
            List<String> listed = configured.get(kind.name());
            if (listed == null) {
                return;
            }
            List<String> real = listed.stream().filter(offered::contains).toList();
            if (real.size() == listed.size()) {
                return;
            }
            List<String> gone = listed.stream().filter(model -> !offered.contains(model)).toList();
            System.err.println("models.json lists " + kind + " models this account cannot use: "
                    + String.join(", ", gone) + " - ignoring them");
            kept.put(kind.name(), replacement(listed, real, placeable.getOrDefault(kind, List.of())));
        });
        return kept;
    }

    /**
     * What a vendor's ladder becomes once the models it cannot run are taken out.
     *
     * <p>Whatever survives, normally. When nothing does, what the vendor offers instead — an empty
     * ladder would hand every job back to the CLI's own default and silently undo the tiering. A
     * vendor offering nothing this code can rank keeps its stale list, which is at least a list.
     */
    private static List<String> replacement(List<String> listed, List<String> real,
                                            List<String> offered) {
        if (!real.isEmpty()) {
            return real;
        }
        return offered.isEmpty() ? listed : offered;
    }

    public List<String> forVendor(AgentKind kind) {
        return byVendor.getOrDefault(kind.name(), List.of());
    }

    /** Where a model sits on the vendor's slider, or -1 if it is not a listed tier. */
    public int indexOf(AgentKind kind, String model) {
        return forVendor(kind).indexOf(model);
    }

    /** Writes this ladder to {@code models.json} under a Forge root. */
    public void write(Path forgeRoot) {
        save(forgeRoot.resolve("models.json"));
    }

    private void save(Path file) {
        try {
            // A root that does not exist yet is normal on first use; creating it here is the
            // difference between "forge ui --root somewhere-new" working and crashing.
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(byVendor));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> ordered(Map<?, ?> raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        raw.forEach((vendor, models) ->
                out.put(String.valueOf(vendor), List.copyOf((List<String>) models)));
        return out;
    }

    /**
     * The model at a complexity band on a vendor's ladder.
     *
     * <p>Proportional rather than absolute, so a vendor offering six tiers and one offering four both
     * get a sensible answer, and adding a model to {@code models.json} shifts the bands without
     * anything here needing to change.
     *
     * <p>Here rather than on {@link Complexity} because the enum is a judgement about the work and
     * knows nothing about models — which is the whole point of splitting the two, and was the last
     * dependency pointing the wrong way inside {@code spec}.
     */
    public String at(Complexity complexity, AgentKind kind) {
        List<String> ladder = forVendor(kind);
        if (ladder.isEmpty()) {
            return "";
        }
        int index = (int) Math.round(complexity.position() * (ladder.size() - 1));
        return ladder.get(Math.clamp(index, 0, ladder.size() - 1));
    }
}
