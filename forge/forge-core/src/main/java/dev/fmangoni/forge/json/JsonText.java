package dev.fmangoni.forge.json;

import lombok.experimental.UtilityClass;

/**
 * Finds JSON inside prose.
 *
 * <p>Models asked for "JSON only" routinely wrap it in a fenced code block or add a sentence of
 * preamble. Tightening the prompt and hoping is not a fix; scanning for the first balanced object
 * handles every one of those shapes and costs nothing.
 */
@UtilityClass
public class JsonText {

    /**
     * Returns the first balanced {@code {...}} run, or {@code null} if there is none.
     *
     * <p>Tracks string literals so a brace inside a quoted value cannot end the object early —
     * which matters here, because the values are model-written English full of punctuation.
     */
    public String firstObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                inString = !(c == '"' && !escaped);
                escaped = c == '\\' && !escaped;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
