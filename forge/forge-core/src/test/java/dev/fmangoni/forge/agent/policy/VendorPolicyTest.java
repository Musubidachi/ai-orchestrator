package dev.fmangoni.forge.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.model.VendorSettings;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which vendor gets handed a whole project, when the plans are not the same size.
 *
 * <p>Forge cannot measure remaining quota — neither CLI reports it — so this is a setting, and the
 * tests are about it being obeyed narrowly: it governs {@code auto} and nothing else. A setting
 * that also overrode explicit assignments, or that could stop the queue entirely, would be a worse
 * answer than the imbalance it exists to fix.
 */
class VendorPolicyTest {

    @TempDir
    Path root;

    private VendorPolicy policy(boolean claudeBuilds, boolean codexBuilds) {
        Map<String, VendorSettings> map = new LinkedHashMap<>();
        map.put("CLAUDE", new VendorSettings(claudeBuilds, 1));
        map.put("CODEX", new VendorSettings(codexBuilds, 1));
        return new VendorPolicy(map);
    }

    private Spec spec(String id, String assign) throws IOException {
        Path file = root.resolve(id + "-thing.md");
        Files.writeString(file, """
                ---
                id: "%s"
                title: Thing %s
                assign: %s
                acceptance:
                  - echo ok
                ---

                ## Goal
                Thing.
                """.formatted(id, id, assign), StandardCharsets.UTF_8);
        return SpecLoader.load(file);
    }

    @Test
    @DisplayName("both vendors build until someone says otherwise")
    void defaultsToBoth() {
        VendorPolicy loaded = VendorPolicy.load(root);

        assertTrue(loaded.buildsNewProjects(AgentKind.CLAUDE));
        assertTrue(loaded.buildsNewProjects(AgentKind.CODEX));
        assertTrue(Files.isRegularFile(root.resolve("vendors.json")),
                "the file is written on first use so it can be found and edited");
    }

    @Test
    @DisplayName("Gemini arrives as a reviewer, not a builder")
    void geminiDoesNotBuildByDefault() {
        // Its free tier is a daily request cap: enough for a review, which is one read-only turn,
        // and not for a build, which is a dozen turns carrying a growing conversation.
        assertFalse(VendorPolicy.load(root).buildsNewProjects(AgentKind.ANTIGRAVITY));
    }

    @Test
    @DisplayName("a vendor missing from the file gets its default, not a blanket yes")
    void absentVendorsGetTheirOwnDefault() throws IOException {
        // The shape every existing install is in: vendors.json was written before Gemini existed.
        Files.writeString(root.resolve("vendors.json"),
                "{\"CLAUDE\":{\"builds\":true,\"concurrency\":2}}");

        VendorPolicy loaded = VendorPolicy.load(root);

        assertTrue(loaded.buildsNewProjects(AgentKind.CODEX), "an absent paid vendor still builds");
        assertFalse(loaded.buildsNewProjects(AgentKind.ANTIGRAVITY),
                "an upgrade must not quietly hand the free tier a project of its own");
    }

    @Test
    @DisplayName("a policy built without the file still knows Gemini does not build")
    void emptyPolicyKeepsTheDefault() {
        // Assignment starts with an empty policy. When the fallback was a bare Vendor — which
        // builds — that path disagreed with the file, and the two defaults would have drifted.
        VendorPolicy empty = new VendorPolicy(new LinkedHashMap<>());

        assertTrue(empty.buildsNewProjects(AgentKind.CLAUDE));
        assertFalse(empty.buildsNewProjects(AgentKind.ANTIGRAVITY));
    }

    @Test
    @DisplayName("a hand-edited file with a mistake in it does not stop a run")
    void unreadableFileFallsBackToBoth() throws IOException {
        Files.writeString(root.resolve("vendors.json"), "{ not json", StandardCharsets.UTF_8);

        VendorPolicy loaded = VendorPolicy.load(root);

        assertTrue(loaded.buildsNewProjects(AgentKind.CLAUDE));
        assertTrue(loaded.buildsNewProjects(AgentKind.CODEX));
    }

    @Test
    @DisplayName("the setting survives a round trip through the file")
    void roundTrips() throws IOException {
        Files.writeString(root.resolve("vendors.json"),
                "{\"CLAUDE\": {\"builds\": true, \"concurrency\": 2},"
                        + " \"CODEX\": {\"builds\": false}}", StandardCharsets.UTF_8);

        VendorPolicy loaded = VendorPolicy.load(root);

        assertTrue(loaded.buildsNewProjects(AgentKind.CLAUDE));
        assertFalse(loaded.buildsNewProjects(AgentKind.CODEX));
        assertFalse(loaded.nobodyBuilds());
        assertEquals(2, loaded.concurrency(AgentKind.CLAUDE));
        assertEquals(1, loaded.concurrency(AgentKind.CODEX), "an omitted field keeps its default");
    }

    @Test
    @DisplayName("a new install starts Codex without the features that reach outside a repository")
    void codexStartsWithoutThePersonsOwnTools() {
        VendorPolicy fresh = VendorPolicy.load(root);

        assertEquals(List.of("computer_use", "browser_use", "apps", "plugins"),
                fresh.of(AgentKind.CODEX).getDisabledFeatures());
        assertNull(fresh.of(AgentKind.CODEX).getWindowsSandbox(),
                "which sandbox works is a fact about a machine, so there is no default");
        assertTrue(fresh.of(AgentKind.CLAUDE).getDisabledFeatures().isEmpty());
    }

    @Test
    @DisplayName("Codex's sandbox and disabled features are read from the file as written")
    void codexOptionsAreRead() throws IOException {
        Files.writeString(root.resolve("vendors.json"),
                "{\"CODEX\": {\"builds\": false, \"windowsSandbox\": \"unelevated\","
                        + " \"disabledFeatures\": [\"computer_use\"]}}", StandardCharsets.UTF_8);

        VendorSettings codex = VendorPolicy.load(root).of(AgentKind.CODEX);

        assertEquals("unelevated", codex.getWindowsSandbox());
        assertEquals(List.of("computer_use"), codex.getDisabledFeatures(),
                "what the file says, not the default: leaving a feature on is the person's call");
    }

    @Test
    @DisplayName("a file written before these settings existed still loads, overriding nothing")
    void olderFilesOverrideNothing() throws IOException {
        Files.writeString(root.resolve("vendors.json"),
                "{\"CODEX\": {\"builds\": false, \"concurrency\": 1}}", StandardCharsets.UTF_8);

        VendorSettings codex = VendorPolicy.load(root).of(AgentKind.CODEX);

        assertNull(codex.getWindowsSandbox());
        assertTrue(codex.getDisabledFeatures().isEmpty());
    }

    @Test
    @DisplayName("concurrency never drops below one, whatever the file says")
    void concurrencyHasAFloor() throws IOException {
        Files.writeString(root.resolve("vendors.json"),
                "{\"CLAUDE\": {\"concurrency\": 0}, \"CODEX\": {\"concurrency\": -3}}",
                StandardCharsets.UTF_8);

        VendorPolicy loaded = VendorPolicy.load(root);

        // A zero here would mean a vendor nothing could ever reach, which is a stopped queue
        // rather than a cautious one.
        assertEquals(1, loaded.concurrency(AgentKind.CLAUDE));
        assertEquals(1, loaded.concurrency(AgentKind.CODEX));
    }
}
