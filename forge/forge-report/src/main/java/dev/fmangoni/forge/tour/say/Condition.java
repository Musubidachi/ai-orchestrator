package dev.fmangoni.forge.tour.say;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * A test as a person would say it: {@code x == null} is "x is missing", not "x equals equals null".
 *
 * <p>Conditions are where a method's rules live, so these are the lines most worth hearing — and
 * the ones a synthesiser makes least sense of, because read literally they are a run of symbols.
 */
@UtilityClass
public class Condition {

    private final Pattern MISSING = Pattern.compile("^(.+?)\\s*==\\s*null$");
    private final Pattern PRESENT = Pattern.compile("^(.+?)\\s*!=\\s*null$");
    private final Pattern SAME = Pattern.compile("^(.+?)\\s*==\\s*(.+)$");
    private final Pattern DIFFERENT = Pattern.compile("^(.+?)\\s*!=\\s*(.+)$");
    private final Pattern MORE = Pattern.compile("^(.+?)\\s*>=?\\s*(.+)$");
    private final Pattern LESS = Pattern.compile("^(.+?)\\s*<=?\\s*(.+)$");
    private final Pattern ASKS = Pattern.compile("^(.+?)\\.(is|has|can|contains|starts|ends)(\\w*)\\s*\\(");

    /** The condition as a clause, or empty when there is no honest way to say it. */
    public String of(String test) {
        String text = test == null ? "" : test.strip();
        while (text.startsWith("(") && text.endsWith(")") && balanced(text)) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return "";
        }
        String joined = joined(text);
        if (!joined.isEmpty()) {
            return joined;
        }
        if (text.startsWith("!")) {
            return negated(of(text.substring(1)));
        }
        return comparison(text);
    }

    /**
     * The opposite of a clause, said the way the clause was.
     *
     * <p>"lines is not empty", not "not lines is empty": a negation belongs on the verb, and a
     * listener hearing the word "not" three words early has to hold the whole clause to use it.
     */
    private String negated(String clause) {
        if (clause.isEmpty()) {
            return "";
        }
        int verb = clause.indexOf(" is ");
        return verb < 0 ? "not " + clause : clause.substring(0, verb) + " is not " + clause.substring(verb + 4);
    }

    private String joined(String text) {
        int and = splitAt(text, "&&");
        if (and > 0) {
            return both(of(text.substring(0, and)), of(text.substring(and + 2)), " and ");
        }
        int or = splitAt(text, "||");
        if (or > 0) {
            return both(of(text.substring(0, or)), of(text.substring(or + 2)), " or ");
        }
        return "";
    }

    private String both(String left, String right, String word) {
        if (left.isEmpty() || right.isEmpty()) {
            return "";
        }
        return left + word + right;
    }

    private String comparison(String text) {
        Matcher missing = MISSING.matcher(text);
        if (missing.matches()) {
            return with(missing.group(1), " is missing");
        }
        Matcher present = PRESENT.matcher(text);
        if (present.matches()) {
            return with(present.group(1), " is there");
        }
        Matcher asks = ASKS.matcher(text);
        if (asks.find()) {
            String question = (asks.group(2) + " " + Phrase.said(asks.group(3))).strip();
            return with(asks.group(1), " " + question);
        }
        return between(text);
    }

    private String between(String text) {
        Matcher same = SAME.matcher(text);
        if (same.matches()) {
            return pair(same, " is ");
        }
        Matcher different = DIFFERENT.matcher(text);
        if (different.matches()) {
            return pair(different, " is not ");
        }
        Matcher more = MORE.matcher(text);
        if (more.matches()) {
            return pair(more, " is past ");
        }
        Matcher less = LESS.matcher(text);
        if (less.matches()) {
            return pair(less, " is under ");
        }
        return Phrase.of(text);
    }

    private String pair(Matcher matcher, String verb) {
        String left = Phrase.of(matcher.group(1));
        String right = Phrase.of(matcher.group(2));
        return left.isEmpty() || right.isEmpty() ? "" : left + verb + right;
    }

    private String with(String subject, String verb) {
        String said = Phrase.of(subject);
        return said.isEmpty() ? "" : said + verb;
    }

    /** True when the outer brackets are a pair, rather than {@code (a) && (b)} meeting in the middle. */
    private boolean balanced(String text) {
        int depth = 0;
        for (int at = 0; at < text.length(); at++) {
            char letter = text.charAt(at);
            depth += letter == '(' ? 1 : letter == ')' ? -1 : 0;
            if (depth == 0 && at < text.length() - 1) {
                return false;
            }
        }
        return depth == 0;
    }

    /** Where a two-character operator sits at bracket depth zero, or -1 when it does not appear. */
    private int splitAt(String text, String operator) {
        int depth = 0;
        for (int at = 0; at < text.length() - 1; at++) {
            char letter = text.charAt(at);
            depth += letter == '(' ? 1 : letter == ')' ? -1 : 0;
            if (depth == 0 && text.startsWith(operator, at)) {
                return at;
            }
        }
        return -1;
    }
}
