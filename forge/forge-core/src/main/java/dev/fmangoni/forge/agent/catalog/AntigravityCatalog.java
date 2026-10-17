package dev.fmangoni.forge.agent.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The models Antigravity will accept, asked of the CLI itself.
 *
 * <p>No cache file to read here, so this runs {@code agy models} — which costs no quota but does
 * reach the network, and therefore belongs nowhere near a page load. It is called when the ladder
 * is being written, not when it is being read.
 *
 * <p>Google's own models only, though {@code agy} also fronts Anthropic's and OpenAI's. Picking one
 * of those to review a project the same vendor built would quietly undo the cross-review: the value
 * of a second opinion is that it does not share the first one's blind spots. Anyone who wants them
 * can add them to {@code models.json} by hand.
 */
@UtilityClass
public class AntigravityCatalog {

    private final Pattern MODEL = Pattern.compile("^(gemini-(\\d+(?:\\.\\d+)?)-(flash|pro)-(\\w+))\\b");

    /** Cheapest first: the fast family before the capable one, then version, then effort. */
    private final List<String> EFFORT = List.of("low", "medium", "high");

    /** The models this account can use, cheapest first, or empty when {@code agy} is not around. */
    public List<String> available() {
        List<Matcher> found = new ArrayList<>();
        for (String line : lines()) {
            Matcher matcher = MODEL.matcher(line.strip());
            if (matcher.find()) {
                found.add(matcher);
            }
        }
        found.sort(Comparator.<Matcher>comparingInt(model -> model.group(3).equals("pro") ? 1 : 0)
                .thenComparingDouble(model -> Double.parseDouble(model.group(2)))
                .thenComparingInt(model -> effortOf(model.group(4))));
        return found.stream().map(matcher -> matcher.group(1)).distinct().toList();
    }

    private int effortOf(String effort) {
        int at = EFFORT.indexOf(effort);
        return at < 0 ? EFFORT.size() : at;
    }

    private List<String> lines() {
        try {
            Process listing = new ProcessBuilder("agy", "models").redirectErrorStream(true).start();
            String output = new String(listing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!listing.waitFor(60, TimeUnit.SECONDS) || listing.exitValue() != 0) {
                return List.of();
            }
            return output.lines().toList();
        } catch (IOException e) {
            // Not installed, which is normal: Antigravity is the optional third vendor.
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
