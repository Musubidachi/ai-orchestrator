package dev.fmangoni.forge.design;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads what a vendor left in its folder, and keeps only what can safely be shown.
 *
 * <p>Nothing here judges a design. It checks the things that would make an option unusable or
 * unwelcome on the page: a file that is not there, one that is not a page, one that wanders out of
 * the folder, and one that reaches for the network. A mock-up is opened in a person's browser, and
 * a page that fetches a font from somebody's server tells that server it was opened.
 */
public final class DesignCheck {

    /** The only names an option may have: no folders, nothing clever. */
    private static final Pattern NAME = Pattern.compile("option-[1-9]\\d?\\.html");

    /**
     * A page that names the network. Not the only guard: the editor also serves these pages with a
     * policy that blocks every load, so a way this misses is refused by the browser anyway.
     */
    private static final Pattern NETWORK = Pattern.compile(
            "(?i)(\\b(src|srcset|href|action|poster|data|background)\\s*=\\s*[\"']?[^\"'>]*?(https?:)?//"
                    + "|url\\(\\s*[\"']?\\s*(https?:)?//|@import|http-equiv\\s*=\\s*[\"']?refresh)");

    private static final long SMALLEST = 300;
    private static final long LARGEST = 800_000;

    private final Path studio;
    private final AgentKind vendor;
    private final Path runRoot;

    /** Why an option was dropped, one line each, for the board to show. */
    private final List<String> dropped = new ArrayList<>();

    /**
     * @param studio  the vendor's own folder
     * @param runRoot the run's folder, which the options' paths are recorded relative to
     */
    public DesignCheck(Path studio, AgentKind vendor, Path runRoot) {
        this.studio = studio;
        this.vendor = vendor;
        this.runRoot = runRoot;
    }

    /** The options worth showing, at most {@code most} of them. */
    public List<DesignOption> usable(int most) {
        List<DesignOption> kept = new ArrayList<>();
        for (JsonNode entry : listed()) {
            if (kept.size() >= most) {
                break;
            }
            String file = entry.path("file").asText("").strip();
            String problem = problemWith(file);
            if (problem != null) {
                dropped.add(vendor + " " + (file.isBlank() ? "an option with no file" : file) + ": " + problem);
                continue;
            }
            int number = kept.size() + 1;
            kept.add(new DesignOption(
                    vendor.name() + "-" + number,
                    vendor.name(),
                    orElse(entry.path("name").asText(""), "Option " + number),
                    entry.path("pitch").asText("").strip(),
                    runRoot.relativize(studio.resolve(file)).toString().replace('\\', '/')));
        }
        return kept;
    }

    public List<String> dropped() {
        return List.copyOf(dropped);
    }

    /** The vendor's own list, or its option files in order when it wrote the pages and not the list. */
    private List<JsonNode> listed() {
        Path index = studio.resolve(DesignBrief.INDEX);
        List<JsonNode> entries = new ArrayList<>();
        if (Files.isRegularFile(index)) {
            try {
                JsonNode root = Json.mapper().readTree(Files.readString(index, StandardCharsets.UTF_8));
                (root.isArray() ? root : root.path("options")).forEach(entries::add);
            } catch (IOException | RuntimeException e) {
                dropped.add(vendor + " " + DesignBrief.INDEX + " could not be read: " + e.getMessage());
            }
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        for (int n = 1; n <= 9; n++) {
            if (Files.isRegularFile(studio.resolve("option-" + n + ".html"))) {
                entries.add(Json.mapper().createObjectNode().put("file", "option-" + n + ".html"));
            }
        }
        return entries;
    }

    private String problemWith(String file) {
        if (!NAME.matcher(file).matches()) {
            return "not a file name an option may have";
        }
        Path page = studio.resolve(file);
        if (!Files.isRegularFile(page)) {
            return "listed but never written";
        }
        try {
            long size = Files.size(page);
            if (size < SMALLEST) {
                return "too small to be a picture of anything";
            }
            if (size > LARGEST) {
                return "too large to be a single page";
            }
            if (NETWORK.matcher(Files.readString(page, StandardCharsets.UTF_8)).find()) {
                return "loads something from the network";
            }
        } catch (IOException e) {
            return "could not be read";
        }
        return null;
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.strip();
    }
}
