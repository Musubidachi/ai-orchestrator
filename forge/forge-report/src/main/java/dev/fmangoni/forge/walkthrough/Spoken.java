package dev.fmangoni.forge.walkthrough;

import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Turns what is written for the eye into something worth hearing.
 *
 * <p>A walkthrough is read aloud, and the things a codebase is full of — {@code RaceLoop.java},
 * {@code npm run test:e2e}, {@code 4821} seconds — are exactly the things a speech synthesiser
 * makes a mess of. A file name read letter by letter, or a number read as digits, costs the
 * listener more attention than the sentence around it is worth.
 */
@UtilityClass
public class Spoken {

    /** A class or file name as words: {@code RaceLoop.java} becomes "Race Loop". */
    public String typeName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String bare = name.replace('\\', '/');
        bare = bare.substring(bare.lastIndexOf('/') + 1);
        bare = bare.contains(".") ? bare.substring(0, bare.lastIndexOf('.')) : bare;
        return words(bare);
    }

    /** Splits camel case and underscores into separate words, leaving acronyms whole. */
    public String words(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
    }

    /**
     * A duration a person would say out loud.
     *
     * <p>"Two hours and twenty minutes", not "8,421 seconds": the listener wants the size of the
     * thing, and a number they have to convert in their head is a number they stop listening to.
     */
    public String duration(long seconds) {
        if (seconds < 90) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " minutes";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        String hourPart = hours + (hours == 1 ? " hour" : " hours");
        return rest == 0 ? hourPart : hourPart + " and " + rest + " minutes";
    }

    /**
     * Model-written prose with its markup taken out.
     *
     * <p>Handoffs and findings come back full of backticks, asterisks and hashes, and a synthesiser
     * says "asterisk asterisk" rather than emphasising anything. Whatever was being marked up is
     * kept; only the marks go.
     */
    public String plain(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[`*#~]", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** {@code 1} thing, {@code 2} things — so a sentence reads as English however many there are. */
    public String count(long many, String noun) {
        return many + " " + (many == 1 ? noun : plural(noun));
    }

    private String plural(String noun) {
        if (noun.endsWith("y") && !noun.endsWith("ay") && !noun.endsWith("ey")) {
            return noun.substring(0, noun.length() - 1) + "ies";
        }
        return noun.endsWith("s") || noun.endsWith("x") || noun.endsWith("ch") ? noun + "es" : noun + "s";
    }

    /** A list as speech: "a, b and c" — the last comma is what makes it sound written rather than said. */
    public String and(List<String> items) {
        List<String> kept = items.stream().filter(item -> item != null && !item.isBlank()).toList();
        if (kept.isEmpty()) {
            return "";
        }
        if (kept.size() == 1) {
            return kept.getFirst();
        }
        return String.join(", ", kept.subList(0, kept.size() - 1)) + " and " + kept.getLast();
    }

    /**
     * A command as something sayable, or a short stand-in when it is not.
     *
     * <p>A shell line is punctuation a synthesiser reads out symbol by symbol. The first couple of
     * words say which command it was, which is all a listener needs to place it.
     */
    public String command(String command) {
        if (command == null || command.isBlank()) {
            return "a command";
        }
        String[] parts = command.strip().split("\\s+");
        String head = String.join(" ", List.of(parts).subList(0, Math.min(3, parts.length)));
        return head.toLowerCase(Locale.ROOT).replace("&&", "then").replace("|", " ");
    }

    /** One sentence, ending in a full stop so the voice drops at the end of it. */
    public String sentence(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!")
                ? trimmed : trimmed + ".";
    }
}
