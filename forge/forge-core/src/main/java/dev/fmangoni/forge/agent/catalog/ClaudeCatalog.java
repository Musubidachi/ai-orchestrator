package dev.fmangoni.forge.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * The models Claude will actually accept from this account.
 *
 * <p>Read from the catalog the CLI caches for its own model picker. Aliases rather than full
 * identifiers — {@code sonnet} rather than {@code claude-sonnet-5} — because an alias follows the
 * latest of its line, and pinning a version means a spec quietly keeps using last year's model
 * after everything else has moved on.
 *
 * <p>Only the models the picker shows in its main list. The overflow section is previous versions,
 * kept so an open session can carry on using what it started with, and putting them on a tier
 * ladder would mean choosing an older model on purpose.
 */
@UtilityClass
public class ClaudeCatalog {

    /**
     * Cheapest first. Anything unrecognised is listed as available but kept off any ladder built
     * from discovery; see {@link #placeable}.
     *
     * <p>The catalog says what exists, not what it costs, and the cost order is the one thing a
     * ladder needs. This is the judgement; correcting it is what {@code models.json} is for.
     */
    private final List<String> RANK = List.of("haiku", "sonnet", "opus");

    /**
     * Left out deliberately, though the account can use it.
     *
     * <p>A preference rather than a limitation: Fabrizio does not want it spending a build's
     * quota. Availability is discovered; what to do with it is a choice, and this is the one
     * place that choice is written down.
     */
    private final List<String> UNWANTED = List.of("fable");

    /** The models this account can use, cheapest first, or empty when the catalog is unreadable. */
    public List<String> available() {
        return available(home().resolve("cache").resolve("model-catalog"));
    }

    public List<String> available(Path catalogDir) {
        Optional<Path> newest = newestIn(catalogDir);
        if (newest.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode models = Json.mapper().readTree(Files.readString(newest.get()))
                    .path("catalog").path("config").path("models");
            List<String> aliases = new ArrayList<>();
            models.forEach(model -> {
                String alias = aliasOf(model);
                if (!alias.isEmpty() && !aliases.contains(alias) && !UNWANTED.contains(alias)) {
                    aliases.add(alias);
                }
            });
            aliases.sort(Comparator.comparingInt(ClaudeCatalog::rankOf));
            return List.copyOf(aliases);
        } catch (Exception e) {
            System.err.println("Ignoring unreadable " + newest.get() + ": " + e.getMessage());
            return List.of();
        }
    }

    private String aliasOf(JsonNode model) {
        if (!"main".equals(model.path("section").asText())) {
            return "";
        }
        return model.path("short_name").asText("").toLowerCase(Locale.ROOT).strip();
    }

    /**
     * The aliases this class knows how to rank, in ladder order; anything else is left off.
     *
     * <p>{@link #available()} lists every model the account can use, which is what deciding
     * whether a configured model still exists needs. A ladder built from discovery needs less:
     * an alias not in {@link #RANK} has no known cost, and putting it at either end is a guess
     * with a price. At the top, every planning turn and every review would run on it, as well as
     * any escalation — which is how Fable, top of the list and refusing for want of credits, took
     * seven turns from one project. At the bottom, every per-feature check would. So a new model
     * reaches a ladder when a person puts it in {@code models.json}; {@code forge models} lists it
     * as available and unused until then.
     */
    public List<String> placeable(List<String> aliases) {
        return aliases.stream().filter(RANK::contains)
                .sorted(Comparator.comparingInt(ClaudeCatalog::rankOf))
                .toList();
    }

    private int rankOf(String alias) {
        int at = RANK.indexOf(alias);
        return at < 0 ? RANK.size() : at;
    }

    /**
     * The most recently fetched catalog file.
     *
     * <p>The CLI writes one per surface and per organisation, and they can disagree after a plan
     * changes. The newest is the one it last had confirmed.
     */
    private Optional<Path> newestIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(ClaudeCatalog::fetchedAt));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private long fetchedAt(Path file) {
        try {
            return Json.mapper().readTree(Files.readString(file)).path("fetchedAt").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** {@code CLAUDE_CONFIG_DIR} when it is set, which is how the CLI itself finds its own files. */
    private Path home() {
        String set = System.getenv("CLAUDE_CONFIG_DIR");
        return set != null && !set.isBlank()
                ? Path.of(set)
                : Path.of(System.getProperty("user.home")).resolve(".claude");
    }
}
