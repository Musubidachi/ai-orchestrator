package dev.fmangoni.forge.tour.say;

import dev.fmangoni.forge.walkthrough.Spoken;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * A Java expression as words.
 *
 * <p>Identifiers are already English written without spaces — {@code writeWav}, {@code runPaths},
 * {@code MODULE} — so the useful thing to do with one is put the spaces back. What this will not do
 * is guess: an expression it has no reading for comes back empty, and the line it came from is left
 * out of the tour rather than described wrongly.
 */
@UtilityClass
public class Phrase {

    private final Pattern TEXT = Pattern.compile("\"([^\"]{1,60})\"");
    private final Pattern NEW = Pattern.compile("^new\\s+([A-Z]\\w*)\\s*\\(");
    private final Pattern CALL_ON = Pattern.compile("^([A-Za-z_]\\w*)\\.(\\w+)\\s*\\(");
    private final Pattern CALL = Pattern.compile("^(\\w+)\\s*\\(");
    private final Pattern FIELD = Pattern.compile("^([A-Za-z_]\\w*)\\.(\\w+)$");
    private final Pattern NAME = Pattern.compile("^[A-Za-z_]\\w*$");
    private final Pattern NUMBER = Pattern.compile("^-?\\d[\\d_.]*[LlDdFf]?$");

    /** The expression said aloud, or empty when there is no honest way to say it. */
    public String of(String expression) {
        String text = expression == null ? "" : expression.strip();
        while (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return "";
        }
        String plain = keyword(text);
        if (plain != null) {
            return plain;
        }
        return structure(text);
    }

    private String keyword(String text) {
        return switch (text) {
            case "true" -> "yes";
            case "false" -> "no";
            case "null" -> "nothing";
            case "this" -> "itself";
            default -> null;
        };
    }

    private String structure(String text) {
        Matcher literal = TEXT.matcher(text);
        if (text.startsWith("\"") && literal.find()) {
            return "the words " + literal.group(1);
        }
        if (NUMBER.matcher(text).matches()) {
            return text.replaceAll("[LlDdFf]$", "");
        }
        Matcher made = NEW.matcher(text);
        if (made.find()) {
            return "a new " + Spoken.words(made.group(1)).toLowerCase(Locale.ROOT);
        }
        Matcher on = CALL_ON.matcher(text);
        if (on.find()) {
            return said(on.group(2)) + " from " + said(on.group(1));
        }
        Matcher call = CALL.matcher(text);
        if (call.find()) {
            return said(call.group(1));
        }
        Matcher field = FIELD.matcher(text);
        if (field.matches()) {
            return said(field.group(2)) + " of " + said(field.group(1));
        }
        return NAME.matcher(text).matches() ? said(text) : "";
    }

    /** An identifier with its spaces back, said the way a person reads it out. */
    String said(String identifier) {
        return Spoken.words(identifier).toLowerCase(Locale.ROOT);
    }
}
