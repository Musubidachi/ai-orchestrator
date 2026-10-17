package dev.fmangoni.forge.tour.say;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.read.model.ParsedMethod;
import dev.fmangoni.forge.tour.read.model.ParsedType;
import dev.fmangoni.forge.walkthrough.Spoken;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * One class as a chapter: what it is for, then its methods in the order they are written.
 *
 * <p>Source order rather than any order this could invent. A file is written to be read downwards,
 * and the author's arrangement — the entry point first, the details under it — is itself an
 * explanation that nothing here is in a position to improve on.
 */
@UtilityClass
public class TypeStory {

    /** Enough of the declaration on screen to see what kind of thing this is. */
    private final int OPENING_LINES = 24;

    public Chapter of(ParsedType type) {
        List<Scene> scenes = new ArrayList<>();
        scenes.add(opening(type));
        for (ParsedMethod method : type.methods()) {
            scenes.addAll(MethodStory.of(type, method));
        }
        return new Chapter(type.name(), where(type), scenes);
    }

    private Scene opening(ParsedType type) {
        int declared = Math.max(1, declarationLine(type));
        int from = Math.max(1, declared - 2);
        CodeView view = new CodeView(
                type.file(), from, type.between(from, from + OPENING_LINES - 1), declared, declared);
        return new Scene(type.name(), said(type), view);
    }

    private String said(ParsedType type) {
        String name = Spoken.typeName(type.name());
        String note = Doc.prose(type.doc());
        String opening = note.isEmpty()
                ? Spoken.sentence("The " + type.kind() + " " + name)
                : Spoken.sentence(name + ". " + note);
        int told = (int) type.methods().stream().filter(method -> !method.constructor()).count();
        return told == 0 ? opening : opening + " It has " + Spoken.count(told, "method") + ".";
    }

    /** The line the type is declared on: the first line that is not import, package or comment. */
    private int declarationLine(ParsedType type) {
        List<String> lines = type.lines();
        for (int at = 0; at < lines.size(); at++) {
            String text = lines.get(at).strip();
            if (text.contains(" " + type.name())
                    && (text.contains("class ")
                            || text.contains("record ")
                            || text.contains("interface ")
                            || text.contains("enum "))) {
                return at + 1;
            }
        }
        return 1;
    }

    private String where(ParsedType type) {
        return type.packageName().isEmpty() ? type.file() : type.packageName() + " · " + type.file();
    }
}
