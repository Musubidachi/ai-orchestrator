package dev.fmangoni.forge.agent.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.model.VendorSettings;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each vendor should be used, when the spec does not say.
 *
 * <p>Forge cannot see how much quota a subscription has left — neither CLI reports it — so it
 * cannot discover that one plan is far smaller than the other. It finds out the way a person does:
 * by running out. That makes this a setting rather than a heuristic, because the only party who
 * knows the shape of the two plans is the one paying for them.
 *
 * <p>Read from {@code vendors.json} at the Forge root, and written there on first use so it can be
 * found and edited.
 */
public record VendorPolicy(Map<String, VendorSettings> byVendor) {

    private static final ObjectMapper JSON = Json.mapper();

    /**
     * The one place a vendor's out-of-the-box settings are decided.
     *
     * <p>Antigravity arrives as a reviewer. Its free tier is a daily request cap: ample for a review,
     * which is one read-only turn, and nowhere near a build, which is a dozen turns carrying a
     * growing conversation. Handing it a project of its own on the first run would spend the day's
     * allowance on one half-built thing and leave nothing to review with. Editable in
     * vendors.json like everything else here.
     */
    private static VendorSettings defaultFor(AgentKind kind) {
        VendorSettings vendor = new VendorSettings();
        vendor.setBuilds(kind != AgentKind.ANTIGRAVITY);
        if (kind == AgentKind.CODEX) {
            vendor.setDisabledFeatures(new ArrayList<>(CODEX_NOT_FOR_AGENTS));
        }
        return vendor;
    }

    /**
     * What a Codex turn is started without, unless vendors.json says otherwise.
     *
     * <p>Each of these reaches outside the repository the agent was given: the desktop, a browser,
     * and the person's connected accounts. The sandbox setting has no default here, because which
     * one works is a fact about a machine rather than about Forge.
     */
    private static final List<String> CODEX_NOT_FOR_AGENTS =
            List.of("computer_use", "browser_use", "apps", "plugins");

    private static Map<String, VendorSettings> defaults() {
        Map<String, VendorSettings> map = new LinkedHashMap<>();
        for (AgentKind kind : AgentKind.values()) {
            map.put(kind.name(), defaultFor(kind));
        }
        return map;
    }

    public static VendorPolicy load(Path forgeRoot) {
        Path file = forgeRoot.resolve("vendors.json");
        if (!Files.isRegularFile(file)) {
            VendorPolicy defaults = new VendorPolicy(defaults());
            defaults.save(file);
            return defaults;
        }
        try {
            Map<String, VendorSettings> read = JSON.readValue(
                    JsonFile.read(file),
                    JSON.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, VendorSettings.class));
            Map<String, VendorSettings> merged = defaults();
            merged.putAll(read);
            VendorPolicy policy = new VendorPolicy(merged);
            if (!read.keySet().containsAll(merged.keySet())) {
                // A file written before a vendor existed is completed on disk, not merely in
                // memory. The whole point of this file is that a person can open it and change
                // their mind; a setting that only exists at runtime cannot be argued with.
                policy.save(file);
            }
            return policy;
        } catch (Exception e) {
            // A hand-edited file with a typo must not stop a run; both vendors working normally is
            // what someone who has never opened this file already expects.
            System.err.println("Ignoring unreadable vendors.json: " + e.getMessage());
            return new VendorPolicy(defaults());
        }
    }

    /**
     * This vendor's settings, or the default for a vendor the policy does not mention.
     *
     * <p>The fallback goes through {@link #defaultFor} rather than constructing a bare
     * {@code VendorSettings}. A plain one builds, and Antigravity must not: a policy assembled without going
     * through {@link #load} — {@code Assignment} starts with an empty one — would otherwise hand
     * the free tier a project of its own and spend the day's allowance on one half-built thing.
     * Two places deciding the same default independently is how they come to disagree.
     */
    public VendorSettings of(AgentKind kind) {
        return byVendor().getOrDefault(kind.name(), defaultFor(kind));
    }

    /** Whether {@code auto} may hand this vendor a project of its own. */
    public boolean buildsNewProjects(AgentKind kind) {
        return of(kind).isBuilds();
    }

    /** Concurrent calls allowed to this vendor; never below one, whatever the file says. */
    public int concurrency(AgentKind kind) {
        return Math.max(1, of(kind).getConcurrency());
    }

    /** True when nobody is willing to build, in which case the setting has to be ignored. */
    public boolean nobodyBuilds() {
        return Arrays.stream(AgentKind.values()).noneMatch(this::buildsNewProjects);
    }

    private void save(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(byVendor()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
