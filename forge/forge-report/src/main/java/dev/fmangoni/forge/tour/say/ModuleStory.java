package dev.fmangoni.forge.tour.say;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.read.model.ParsedType;
import dev.fmangoni.forge.walkthrough.Spoken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The few sentences that open a module, before any of its code is shown.
 *
 * <p>Taken from what the project already says about itself: the {@code CLAUDE.md} beside the module
 * if there is one, otherwise the {@code <description>} in its pom. A module that says nothing about
 * itself gets its size and its packages, which is thin — and thin is the honest answer, rather than
 * a paragraph invented to fill the gap.
 */
@UtilityClass
public class ModuleStory {

    private final Pattern DESCRIPTION = Pattern.compile("<description>\\s*([^<]+?)\\s*</description>");

    public Chapter of(Path module, List<ParsedType> types) {
        String name = module.getFileName().toString();
        return new Chapter(
                Spoken.words(name),
                Spoken.count(types.size(), "class"),
                List.of(Scene.spoken(Spoken.words(name), said(module, name, types))));
    }

    private String said(Path module, String name, List<ParsedType> types) {
        String note = note(module);
        String opening = note.isEmpty()
                ? Spoken.sentence("The " + Spoken.words(name) + " module")
                : Spoken.sentence(Spoken.words(name) + ". " + note);
        long packages = types.stream().map(ParsedType::packageName).distinct().count();
        return opening + " " + Spoken.sentence(
                "It is " + Spoken.count(types.size(), "class")
                        + " in " + Spoken.count(packages, "package"));
    }

    private String note(Path module) {
        String written = firstParagraph(module.resolve("CLAUDE.md"));
        if (!written.isEmpty()) {
            return written;
        }
        Matcher described = DESCRIPTION.matcher(read(module.resolve("pom.xml")));
        return described.find() ? Spoken.plain(described.group(1)) : "";
    }

    /** The first real line under the heading of a module's note, which is its one-line summary. */
    private String firstParagraph(Path note) {
        for (String line : read(note).lines().toList()) {
            String text = line.strip();
            if (!text.isEmpty() && !text.startsWith("#")) {
                return Doc.prose(Spoken.plain(text));
            }
        }
        return "";
    }

    private String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
