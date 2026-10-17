package dev.fmangoni.forge.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.MonetizationSettings;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Reads wishlist entries: a YAML front matter block fenced by {@code ---}, then Markdown prose.
 *
 * <p>Validation is strict and happens at load time rather than at build time. An unattended run
 * that discovers a bad spec three hours in, after spending quota on it, is far worse than one that
 * refuses it in the first second.
 */
@UtilityClass
public class SpecLoader {

    /** Leading {@code ---} fence, the YAML body, then a closing {@code ---} fence on its own line. */
    private final Pattern FRONT_MATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R(.*)\\z", Pattern.DOTALL);

    private final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Loads every {@code .md} file in a directory, ordered by filename.
     *
     * <p>An unreadable spec is reported and skipped rather than thrown. One malformed file — or a
     * stray {@code README.md} someone dropped in the wishlist — must not stop the other nine
     * projects from running overnight. {@link #load(Path)} still throws, so the strictness is
     * preserved where a single spec was explicitly asked for.
     */
    public List<Spec> loadAll(Path wishlistDir) {
        if (!Files.isDirectory(wishlistDir)) {
            return List.of();
        }
        try (var files = Files.list(wishlistDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(SpecLoader::loadOrReport)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read wishlist " + wishlistDir, e);
        }
    }

    private Optional<Spec> loadOrReport(Path file) {
        try {
            return Optional.of(load(file));
        } catch (SpecException e) {
            System.err.println("Skipping spec: " + e.getMessage());
            return Optional.empty();
        }
    }

    public Spec load(Path file) {
        return parse(file, read(file));
    }

    /**
     * Parses and validates spec text that is not (yet) on disk.
     *
     * <p>Exposed so the editor can check a draft through exactly this code path. A separate
     * validator written for the UI would be a second copy of the rules, and the copy that drifts is
     * always the one telling the user their spec is fine.
     *
     * @param source the path to name in any error, real or prospective
     */
    public Spec parse(Path source, String text) {
        Matcher m = FRONT_MATTER.matcher(text.replace("\r\n", "\n"));
        if (!m.matches()) {
            throw new SpecException(source, "missing the '---' front matter block at the top");
        }
        FrontMatter front = parseFrontMatter(source, m.group(1));
        Spec spec = new Spec(source, front, m.group(2).strip());
        validate(spec);
        return spec;
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read spec " + file, e);
        }
    }

    private FrontMatter parseFrontMatter(Path file, String yaml) {
        try {
            return YAML.readValue(yaml, FrontMatter.class);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() drops Jackson's location suffix, which repeats the whole block.
            throw new SpecException(file, "front matter is not valid YAML: " + e.getOriginalMessage());
        }
    }

    /**
     * Rejects specs that cannot be verified.
     *
     * <p>The {@code acceptance} rule is the load-bearing one. Forge decides whether a project is
     * done by running these commands, so a spec without them could only ever be marked done on the
     * agent's own word — which is the single thing this pipeline exists not to do.
     */
    /**
     * What an id may contain.
     *
     * <p>The id becomes a directory under {@code runs/} — the run's state, and the working tree an
     * agent is handed with permission to do anything in it. It was not checked at all, so an id of
     * {@code ../../word-processor} would have scaffolded a project, and started an agent, inside one of
     * the real repositories beside Forge. Isolation by directory is the whole of the containment this
     * design has, and it rested on nobody ever typing a slash. The id also lands in a script on the
     * report and in links on the dashboard, where a quote would break both.
     */
    private final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private void validate(Spec spec) {
        Path file = spec.source();
        if (isBlank(spec.id())) {
            throw new SpecException(file, "front matter needs an 'id'");
        }
        if (!SAFE_ID.matcher(spec.id()).matches() || spec.id().contains("..")) {
            throw new SpecException(file, "'id' must be letters, digits, '.', '-' or '_', "
                    + "starting with a letter or digit — it names the project's run directory");
        }
        spec.parentId().ifPresent(parent -> {
            if (!SAFE_ID.matcher(parent).matches() || parent.contains("..")) {
                throw new SpecException(file, "'parent' must be a valid spec id");
            }
        });
        if (isBlank(spec.title())) {
            throw new SpecException(file, "front matter needs a 'title'");
        }
        if (spec.acceptance().isEmpty()) {
            throw new SpecException(file, """
                    front matter needs at least one 'acceptance' command.
                        Forge decides a project is done by running these itself; without one there \
                    is nothing to check, and the project could only be marked done on the agent's \
                    own say-so.""");
        }
        if (spec.body().isBlank()) {
            throw new SpecException(file, "no prose below the front matter — the agent needs to know what to build");
        }
        if (spec.maxRepairRounds() < 0) {
            throw new SpecException(file, "'max_repair_rounds' cannot be negative");
        }
        if (spec.parentId().filter(parent -> parent.equals(spec.id())).isPresent()) {
            throw new SpecException(file, "'parent' cannot be the spec's own id");
        }
        spec.serve().ifPresent(serve -> {
            if (!serve.contains("{port}")) {
                throw new SpecException(file, "'serve' must contain {port}: Forge chooses the port, "
                        + "on 127.0.0.1, so it can find the app and never collides with another");
            }
        });
        spec.deploy().ifPresent(deploy -> {
            if (isBlank(deploy.getCommand())) {
                throw new SpecException(file, "'deploy' needs a 'command'");
            }
            if (!isBlank(deploy.getUrl()) && !deploy.getUrl().trim().startsWith("https://")) {
                throw new SpecException(file, "'deploy.url' must be an https:// address");
            }
        });
        MonetizationSettings money = spec.front().getMonetization();
        if (money != null) {
            if (!Set.of("none", "test", "live").contains(spec.adsMode())) {
                throw new SpecException(file, "'monetization.ads' must be none, test or live");
            }
            String provider = money.getProvider() == null ? "" : money.getProvider().trim();
            if (!provider.isEmpty() && !Set.of("adsense", "admob", "custom").contains(provider)) {
                throw new SpecException(file, "'monetization.provider' must be adsense, admob or custom");
            }
            // Ids are public identifiers baked into a page anyone can read. Anything named like a
            // credential is refused here, before it can be written into a build.
            money.getIds().keySet().stream()
                    .filter(key -> key.toLowerCase(Locale.ROOT)
                            .matches(".*(secret|token|password|private|api_?key).*"))
                    .findFirst()
                    .ifPresent(key -> {
                        throw new SpecException(file, "'monetization.ids." + key + "' looks like a "
                                + "credential; only public ids belong in a spec");
                    });
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
