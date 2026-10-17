package dev.fmangoni.forge.agent.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What each vendor says this account can run.
 *
 * <p>Getting this wrong is expensive in a way most mistakes here are not: a model that the
 * subscription cannot reach is not caught by anything until a run has spent its turn being refused,
 * and the refusal arrives as a failed project rather than as an explanation. So the filtering is
 * pinned against the real shapes both CLIs write.
 */
class CatalogTest {

    @TempDir
    Path home;

    // --------------------------------------------------------------------------- Codex

    private Path codexCache() throws IOException {
        Path cache = home.resolve("models_cache.json");
        Files.writeString(cache, """
                {"models": [
                  {"slug": "gpt-6-astra",   "visibility": "list", "priority": 1,  "upgrade": null},
                  {"slug": "gpt-reserve",   "visibility": "hide", "priority": 3,  "upgrade": null},
                  {"slug": "gpt-5.6-sol",   "visibility": "list", "priority": 4,  "upgrade": null},
                  {"slug": "gpt-5.6-luna",  "visibility": "list", "priority": 8,  "upgrade": null},
                  {"slug": "gpt-5.5",       "visibility": "list", "priority": 12,
                   "upgrade": {"model": "gpt-5.6-sol"}}
                ]}
                """, StandardCharsets.UTF_8);
        return cache;
    }

    @Test
    @DisplayName("only what the picker offers, cheapest first")
    void codexListsWhatItOffers() throws IOException {
        assertEquals(List.of("gpt-5.6-luna", "gpt-5.6-sol", "gpt-6-astra"),
                CodexCatalog.available(codexCache()));
    }

    @Test
    @DisplayName("a hidden model and one being retired are both left out")
    void codexDropsWhatItWillRefuse() throws IOException {
        List<String> models = CodexCatalog.available(codexCache());
        assertFalse(models.contains("gpt-reserve"), "a hidden model was offered as a tier");
        // Listed, but the cache says it is being migrated away: choosing it costs a turn to learn.
        assertFalse(models.contains("gpt-5.5"), "a retiring model was offered as a tier");
    }

    @Test
    @DisplayName("no cache is not the same as no models")
    void codexWithoutACache() {
        assertTrue(CodexCatalog.available(home.resolve("absent.json")).isEmpty());
    }

    // -------------------------------------------------------------------------- Claude

    private Path claudeCatalog() throws IOException {
        Path dir = home.resolve("model-catalog");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("old.json"), """
                {"fetchedAt": 100, "catalog": {"config": {"models": [
                  {"id": "claude-opus-4-8", "short_name": "Opus", "section": "main"}
                ]}}}
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("new.json"), """
                {"fetchedAt": 900, "catalog": {"config": {"models": [
                  {"id": "claude-sonnet-5",  "short_name": "Sonnet", "section": "main"},
                  {"id": "claude-fable-5-1", "short_name": "Fable",  "section": "main"},
                  {"id": "claude-opus-5",    "short_name": "Opus",   "section": "main"},
                  {"id": "claude-haiku-4-5", "short_name": "Haiku",  "section": "main"},
                  {"id": "claude-opus-4-6",  "short_name": "Opus",   "section": "overflow"}
                ]}}}
                """, StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    @DisplayName("aliases, cheapest first, from the newest catalog the CLI has")
    void claudeListsAliases() throws IOException {
        assertEquals(List.of("haiku", "sonnet", "opus"), ClaudeCatalog.available(claudeCatalog()));
    }

    @Test
    @DisplayName("Fable is left out on purpose, even though the account can use it")
    void fableIsNotOffered() throws IOException {
        // It reached the top rung by escalation and every repair round after that was refused for
        // want of usage credits, which is a loop rather than a failure.
        assertFalse(ClaudeCatalog.available(claudeCatalog()).contains("fable"));
    }

    @Test
    @DisplayName("previous versions are not tiers; choosing one would be choosing an older model")
    void overflowIsNotATier() throws IOException {
        assertEquals(1, ClaudeCatalog.available(claudeCatalog()).stream()
                .filter(model -> model.equals("opus")).count());
    }

    @Test
    @DisplayName("a model nobody has ranked is available, but no ladder places it by itself")
    void anUnrankedModelIsNotPlaced() {
        // Put at the top it would run every plan and every review, and be escalated to; put at
        // the bottom it would run every feature check. Only a person knows where it belongs.
        List<String> offered = List.of("haiku", "sonnet", "opus", "mythos");

        assertEquals(List.of("haiku", "sonnet", "opus"), ClaudeCatalog.placeable(offered));
        assertEquals(List.of("haiku", "sonnet", "opus"),
                VendorModels.forLadder(Map.of(AgentKind.CLAUDE, offered)).get(AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("no catalog is not the same as no models")
    void claudeWithoutACatalog() {
        assertTrue(ClaudeCatalog.available(home.resolve("absent")).isEmpty());
    }
}
