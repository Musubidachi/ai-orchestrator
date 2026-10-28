package dev.fmangoni.forge.security.session;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command as a report may show it: on one line, cut short, and with anything token-shaped masked.
 *
 * <p>Agents put keys on command lines — an environment variable set inline, a header for a quick
 * request — and the report is read in a browser and kept in the run folder. A long unbroken run of
 * key characters keeps its first four and its length, as the secret scanner does.
 */
public final class Evidence {

    /**
     * A candidate key: long, unbroken, and no slash, so a path is never one. Masked only when it
     * mixes digits and letters the way generated keys do, so a long folder name is left readable.
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_\\-+=]{32,}");

    private static final int MIXED = 6;

    private static final int SHORT = 140;
    private static final int LONG = 600;

    private Evidence() {
    }

    /** For the finding's location, which names it on the ruling form: stays the same on every check. */
    public static String shortened(String command) {
        return cut(masked(command), SHORT);
    }

    public static String masked(String command) {
        String flat = command == null ? "" : command.strip().replaceAll("\\s+", " ");
        Matcher token = TOKEN.matcher(flat);
        StringBuilder out = new StringBuilder();
        while (token.find()) {
            String seen = token.group();
            String shown = randomLooking(seen) ? seen.substring(0, 4) + "…(" + seen.length() + " chars)" : seen;
            token.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        token.appendTail(out);
        return cut(out.toString(), LONG);
    }

    private static boolean randomLooking(String text) {
        long digits = text.chars().filter(Character::isDigit).count();
        long letters = text.chars().filter(Character::isLetter).count();
        return digits >= MIXED && letters >= MIXED;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
