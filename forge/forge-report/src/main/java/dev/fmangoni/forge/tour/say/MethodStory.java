package dev.fmangoni.forge.tour.say;

import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.read.model.ParsedMethod;
import dev.fmangoni.forge.tour.read.model.ParsedType;
import dev.fmangoni.forge.walkthrough.Spoken;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * One method, told a line at a time.
 *
 * <p>A scene per line rather than a paragraph per method, because the point of watching rather than
 * reading is that the line being talked about is the line lit up. The method's own note comes first
 * — the author's sentence about what this is for beats anything that can be inferred from the code
 * under it.
 */
@UtilityClass
public class MethodStory {

    /** Past this, a method is being listed rather than explained; the rest stays on screen. */
    private final int MOST_LINES_TOLD = 10;

    /** Enough of the method on screen to see the shape of it without scrolling. */
    private final int MOST_LINES_SHOWN = 40;

    /** The scenes for one method, or none when there is nothing worth saying about it. */
    public List<Scene> of(ParsedType type, ParsedMethod method) {
        if (method.firstLine() <= 0 || method.lastLine() < method.firstLine()) {
            return List.of();
        }
        List<Scene> scenes = new ArrayList<>();
        String heading = type.name() + "." + name(method);
        String opening = opening(type, method);
        List<Scene> body = body(type, method, heading);
        if (opening.isEmpty() && body.isEmpty()) {
            return List.of();
        }
        if (!opening.isEmpty()) {
            scenes.add(new Scene(heading, opening, view(type, method, method.firstLine(), method.firstLine())));
        }
        scenes.addAll(body);
        return scenes;
    }

    private List<Scene> body(ParsedType type, ParsedMethod method, String heading) {
        List<Scene> scenes = new ArrayList<>();
        for (int line = method.firstLine() + 1; line <= method.lastLine(); line++) {
            if (scenes.size() >= MOST_LINES_TOLD) {
                break;
            }
            String said = LineSense.of(lineAt(type, line));
            if (!said.isEmpty()) {
                scenes.add(new Scene(heading, Spoken.sentence(said), view(type, method, line, line)));
            }
        }
        return scenes;
    }

    private String opening(ParsedType type, ParsedMethod method) {
        String note = Doc.prose(method.doc());
        if (!note.isEmpty()) {
            return Spoken.sentence(note);
        }
        // No note: the name and the parameters are all there is, and for most methods that is
        // genuinely the whole story. A constructor with nothing to say is left out entirely.
        if (method.constructor()) {
            return "";
        }
        String said = Phrase.said(method.name());
        String takes = method.signature().isBlank()
                ? ""
                : " It takes " + Spoken.and(List.of(method.signature().split(",\\s*"))) + ".";
        return Spoken.sentence(type.name() + " " + said) + takes;
    }

    private CodeView view(ParsedType type, ParsedMethod method, int litFrom, int litTo) {
        int from = method.firstLine();
        int to = Math.min(method.lastLine(), from + MOST_LINES_SHOWN - 1);
        // A line being talked about below the window is a line the viewer cannot see: slide the
        // window down to keep it in frame.
        if (litTo > to) {
            to = Math.min(method.lastLine(), litTo + 2);
            from = Math.max(method.firstLine(), to - MOST_LINES_SHOWN + 1);
        }
        return new CodeView(type.file(), from, type.between(from, to), litFrom, litTo);
    }

    private String lineAt(ParsedType type, int line) {
        List<String> one = type.between(line, line);
        return one.isEmpty() ? "" : one.getFirst();
    }

    private String name(ParsedMethod method) {
        return method.constructor() ? "new" : method.name();
    }
}
