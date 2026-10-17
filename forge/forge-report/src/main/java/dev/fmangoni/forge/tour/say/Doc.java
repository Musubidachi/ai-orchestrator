package dev.fmangoni.forge.tour.say;

import dev.fmangoni.forge.walkthrough.Spoken;
import lombok.experimental.UtilityClass;

/**
 * A javadoc note as something worth hearing.
 *
 * <p>These notes are written for the eye: {@code {@code Foo}} wrappers, {@code <p>} tags, em dashes
 * and entity references all say something on a page and nothing out loud. What is left after they
 * come out is the sentence the author actually wrote, which is almost always the best explanation of
 * a method that exists anywhere.
 */
@UtilityClass
public class Doc {

    /** The note as plain prose: tags out, markup out, whitespace collapsed. */
    public String prose(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return "";
        }
        String text = javadoc
                .replaceAll("(?m)^\\s*\\*\\s?", " ")
                .replaceAll("\\{@\\w+\\s+([^}]*)}", "$1")
                .replaceAll("(?i)<p>|<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&mdash;", ", ")
                .replace("&amp;", " and ")
                .replace("&lt;", "less than ")
                .replace("&gt;", "greater than ")
                .replace("&quot;", "\"");
        // Tags that take the rest of the note and are answers to questions nobody asked aloud.
        text = text.replaceAll("(?s)@(param|return|throws|see|author|since)\\b.*", "");
        return Spoken.plain(spoken(text));
    }

    /**
     * The opening sentence of a note, which is where a javadoc says what a thing is for.
     *
     * <p>The paragraphs after it are the reasoning, which is worth reading and too long to hear
     * between one method and the next.
     */
    public String opening(String javadoc) {
        String prose = prose(javadoc);
        if (prose.isEmpty()) {
            return "";
        }
        int stop = endOfFirstSentence(prose);
        return stop < 0 ? prose : prose.substring(0, stop + 1).strip();
    }

    private int endOfFirstSentence(String prose) {
        for (int at = 0; at < prose.length() - 1; at++) {
            // A full stop followed by a space and a capital. "e.g." and "1.18" do not qualify,
            // which is the whole reason this is not a split on ".".
            if (prose.charAt(at) == '.'
                    && prose.charAt(at + 1) == ' '
                    && at + 2 < prose.length()
                    && Character.isUpperCase(prose.charAt(at + 2))) {
                return at;
            }
        }
        return prose.endsWith(".") ? prose.length() - 1 : -1;
    }

    /** Punctuation a synthesiser stumbles over, replaced by the pause it was standing in for. */
    private String spoken(String text) {
        return text.replace('—', ',')
                .replace('–', ',')
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace("…", "...");
    }
}
