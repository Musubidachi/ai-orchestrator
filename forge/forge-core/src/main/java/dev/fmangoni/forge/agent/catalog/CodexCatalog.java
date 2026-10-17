package dev.fmangoni.forge.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The models Codex will actually accept from this account.
 *
 * <p>Read from the cache the CLI keeps for its own model picker, which is the only thing on the
 * machine that knows what a particular subscription can reach. A ChatGPT plan does not see every
 * model OpenAI publishes, and a list written from documentation goes stale the first time a model
 * is retired — which shows up as a run that spends its turn being refused.
 *
 * <p>Two things are dropped. A model the cache hides is one the picker will not offer, and a model
 * carrying an upgrade notice is one being retired in favour of another, so choosing either is a
 * turn spent finding that out.
 *
 * <p>Order is the cache's own priority, reversed: it ranks the most capable first for a picker, and
 * a tier ladder wants the cheapest first.
 */
@UtilityClass
public class CodexCatalog {

    /** The models this account can use, cheapest first, or empty when the cache cannot be read. */
    public List<String> available() {
        return available(home().resolve("models_cache.json"));
    }

    public List<String> available(Path cache) {
        if (!Files.isRegularFile(cache)) {
            return List.of();
        }
        try {
            JsonNode models = Json.mapper().readTree(Files.readString(cache)).path("models");
            List<JsonNode> usable = new ArrayList<>();
            models.forEach(model -> {
                if (offered(model)) {
                    usable.add(model);
                }
            });
            // Descending priority is ascending capability: the picker's first choice is its best.
            usable.sort(Comparator.comparingInt(model -> -model.path("priority").asInt(0)));
            return usable.stream().map(model -> model.path("slug").asText()).toList();
        } catch (Exception e) {
            System.err.println("Ignoring unreadable " + cache + ": " + e.getMessage());
            return List.of();
        }
    }

    private boolean offered(JsonNode model) {
        boolean listed = "list".equals(model.path("visibility").asText());
        boolean retiring = !model.path("upgrade").isMissingNode() && !model.path("upgrade").isNull();
        return listed && !retiring && !model.path("slug").asText().isBlank();
    }

    /** {@code CODEX_HOME} when it is set, which is how the CLI itself finds its own files. */
    private Path home() {
        String set = System.getenv("CODEX_HOME");
        return set != null && !set.isBlank()
                ? Path.of(set)
                : Path.of(System.getProperty("user.home")).resolve(".codex");
    }
}
