package dev.fmangoni.forge.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a file a recheck is about, when the whole of it is more than a review can carry.
 *
 * <p>Keeping the top of the file lost exactly what was asked about whenever the code in question sat
 * near the end: 001's fix to {@code markCallArgumentReads} was at character 31k of a 33k file, past a
 * 30k cut, and the reviewer ruled it "cannot be verified" round after round. A finding carries no line
 * number, so the window is aimed by the names its text mentions — the code identifiers a reviewer
 * writes when it describes a defect — at where the rarest of them is declared, and the rest of the
 * file is left out with a line saying so. With nothing to aim by, it falls back to the top.
 */
public final class FileExcerpt {

    /** How much of the window comes before the anchor: enough to show what encloses it. */
    private static final double LEAD = 0.2;

    /** camelCase, snake_case, kebab-case or dotted names: what code looks like inside prose. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:[-.][A-Za-z_$][A-Za-z0-9_$]*)*");

    private FileExcerpt() {
    }

    /**
     * The file as it stands if it fits in {@code room} characters, otherwise a window of that size
     * around what {@code about} — the findings' own words — names.
     */
    public static String of(String text, int room, List<String> about) {
        if (text.length() <= room) {
            return text;
        }
        int anchor = anchor(text, about);
        // Snapped forward to a whole line, so a window that reaches the end still does.
        int start = nextLineStart(text, Math.max(0, Math.min(anchor - (int) (room * LEAD), text.length() - room)));
        int end = start + room >= text.length() ? text.length() : lineStart(text, start + room);
        if (end <= start) {
            end = Math.min(text.length(), start + room);
        }
        StringBuilder shown = new StringBuilder();
        int lines = lineOf(text, text.length());
        if (start > 0) {
            shown.append("… (lines 1-").append(lineOf(text, start) - 1).append(" left out)\n");
        }
        shown.append(text, start, end);
        if (end < text.length()) {
            shown.append("\n… (lines ").append(lineOf(text, end)).append('-').append(lines)
                    .append(" left out; the file has ").append(lines).append(" lines)\n");
        }
        return shown.toString();
    }

    /** Where the rarest name the findings mention is declared, or first used; 0 when none appears. */
    static int anchor(String text, List<String> about) {
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        for (String name : names(about)) {
            int count = occurrences(text, name);
            if (count > 0 && (count < bestCount || count == bestCount && name.length() > best.length())) {
                best = name;
                bestCount = count;
            }
        }
        return best == null ? 0 : declaration(text, best);
    }

    private static Set<String> names(List<String> about) {
        Set<String> names = new LinkedHashSet<>();
        for (String words : about) {
            if (words == null) {
                continue;
            }
            Matcher matcher = IDENTIFIER.matcher(words);
            while (matcher.find()) {
                String name = matcher.group();
                if (name.length() >= 4 && looksLikeCode(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** A lower-case letter followed by a capital, or a joining character: not an ordinary word. */
    private static boolean looksLikeCode(String name) {
        if (name.indexOf('_') >= 0 || name.indexOf('-') > 0 || name.indexOf('.') > 0) {
            return true;
        }
        for (int i = 1; i < name.length(); i++) {
            if (Character.isLowerCase(name.charAt(i - 1)) && Character.isUpperCase(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int occurrences(String text, String name) {
        int count = 0;
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + name.length())) {
            count++;
        }
        return count;
    }

    /** Words that come before a call, not before a name being declared. */
    private static final Set<String> CALLING = Set.of("return", "new", "await", "throw", "yield", "else", "case", "in");

    /**
     * Where the name is defined: preferably after a type or keyword ({@code void name(}, {@code def
     * name(}, {@code const name =}), else any use outside a comment followed by an opening bracket,
     * brace, colon or equals sign, else its first mention. Documentation usually mentions a method
     * before it is defined, and a call often comes before it too; aiming at either showed what
     * surrounded them and cut off the code the finding was about.
     */
    private static int declaration(String text, String name) {
        List<Integer> uses = new ArrayList<>();
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + name.length())) {
            uses.add(at);
        }
        Integer defined = null;
        for (int at : uses) {
            String before = text.substring(lineStart(text, at), at);
            if (inComment(before) || !definedAt(text, at + name.length())) {
                continue;
            }
            if (declaredAfter(before)) {
                return at;
            }
            if (defined == null) {
                defined = at;
            }
        }
        return defined != null ? defined : uses.get(0);
    }

    private static boolean inComment(String before) {
        String line = before.strip();
        return line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")
                || line.startsWith("#") || line.contains("{@link");
    }

    /** A type, keyword or modifier, then a space, right before the name — not {@code ->}, a dot or a keyword that calls. */
    private static boolean declaredAfter(String before) {
        if (before.isEmpty() || !Character.isWhitespace(before.charAt(before.length() - 1))) {
            return false;
        }
        String head = before.stripTrailing();
        if (head.isEmpty() || head.endsWith("->")) {
            return false;
        }
        char last = head.charAt(head.length() - 1);
        if (!Character.isLetterOrDigit(last) && last != '_' && last != '>' && last != ']') {
            return false;
        }
        int word = head.length();
        while (word > 0 && Character.isLetter(head.charAt(word - 1))) {
            word--;
        }
        return !CALLING.contains(head.substring(word));
    }

    private static boolean definedAt(String text, int after) {
        for (int i = after; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                continue;
            }
            return c == '(' || c == '{' || c == ':' || c == '=';
        }
        return false;
    }

    private static int lineStart(String text, int at) {
        int newline = text.lastIndexOf('\n', Math.max(0, at - 1));
        return at == 0 || newline < 0 ? 0 : newline + 1;
    }

    private static int nextLineStart(String text, int at) {
        if (at == 0 || text.charAt(at - 1) == '\n') {
            return at;
        }
        int newline = text.indexOf('\n', at);
        return newline < 0 ? at : newline + 1;
    }

    private static int lineOf(String text, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
