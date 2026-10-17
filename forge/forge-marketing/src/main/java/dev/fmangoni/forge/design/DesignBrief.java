package dev.fmangoni.forge.design;

import dev.fmangoni.forge.spec.model.Spec;
import lombok.experimental.UtilityClass;

/**
 * The prompt that asks a vendor for a few looks to choose between.
 *
 * <p>What comes back is a picture, not a change. The vendor works in a folder of its own with a copy
 * of the project to read, and draws static pages showing how the program's main screen could look.
 * Nothing it writes reaches the product: the person picks one, and only then is a building agent
 * asked to make the real program look like it.
 *
 * <p>Still pages on purpose. A mock-up that runs is a second implementation, and the question being
 * asked of the person is "which of these do you like the look of", which a still answers.
 */
@UtilityClass
public class DesignBrief {

    /** What the vendor must write, so the check and the brief cannot disagree about the name. */
    public final String INDEX = "options.json";

    /** The design a request for more like it starts from: in the design folder, and in each designer's reference. */
    public final String LIKED_PAGE = "liked.html";

    /**
     * @param note    what the person said they wanted, or blank
     * @param digest  what the builder last wrote about the project, or blank
     * @param options how many looks to draw
     * @param liked   the design to start from, or null to draw from nothing
     * @param extra   the kind of extra to draw beside the current look ({@link DesignBoard#THEME},
     *                {@link DesignBoard#VARIANTS}, {@link DesignBoard#ANIMATION}), or blank for looks
     *                that would replace it
     */
    public String of(Spec spec, String note, String digest, int options, DesignOption liked, String extra) {
        String wanted = liked != null ? startingFrom(liked, note)
                : DesignBoard.isExtra(extra) ? extraWanted(note)
                : note == null || note.isBlank()
                ? "They have not said what they want, so make the options genuinely different from "
                  + "each other rather than three shades of one idea."
                : "What they said they want: " + note.strip() + "\nTake that seriously, and still make "
                  + "the options different enough from each other to be a real choice.";
        wanted += pictures(note);
        String known = digest == null || digest.isBlank() ? "" : """

                ## What the builder last wrote about it

                %s
                """.formatted(digest.strip());
        String draw = liked != null
                ? "Draw %d variations of one look for the program described below".formatted(options)
                : DesignBoard.isExtra(extra) ? extraDraw(extra, options)
                : "Draw %d different looks for the program described below".formatted(options);
        String still = DesignBoard.ANIMATION.equals(extra)
                ? "- The animation itself, playing on the program's main screen: CSS `@keyframes`, "
                  + "looping, a few seconds long. Animation is allowed here; JavaScript still is not."
                : "- A still picture of the look. Draw a moving thing at one moment of its motion.";
        return """
                %7$s, for the person it was \
                built for to choose between. You are designing how it looks. You are not changing \
                it: nothing you write here reaches the program.

                The program already exists and works. `reference/` in this folder holds a copy of \
                its source and any screenshots it has produced. Read them first, so that what you \
                draw is this program's real main screen — the same parts, the same text, the same \
                controls — restyled, and not a screen you imagined.

                %2$s

                For each look, write one file, `option-1.html` up to `option-%1$d.html`:
                - A single self-contained page showing the program's main screen as it would look: \
                its real layout and real content, in the state a person would spend most time in.
                - Everything inline: CSS in a `<style>` block, shapes and artwork as inline SVG or \
                CSS. No JavaScript, no external stylesheet, font, image or link of any kind — the \
                page is opened with scripts off and no network.
                %8$s
                - If the program is not a web page — a desktop window, a terminal tool — draw its \
                window or its output as a page anyway, at about the size it really is.
                - Design for the screen sizes the spec names. Keep text readable and contrast \
                high enough to use.

                Then write `%3$s`, listing them:

                [{"file":"option-1.html","name":"two or three words","pitch":"one sentence saying \
                what is distinctive about this look, for someone choosing between them"}]

                Stay inside what the program is. Do not add features, screens or text it does not \
                have, and respect the spec's non-goals. A look the program could not be given \
                without becoming a different program is not an option.

                Write nothing outside this folder, and do not try to run or change the program.

                ## The program: %4$s

                %5$s
                %6$s""".formatted(options, wanted, INDEX, spec.title(), spec.body(), known, draw, still);
    }

    /**
     * What an extra is, in the opening line: something added beside the current look, never a
     * replacement for it, which is the one thing a designer asked for "a look" assumes otherwise.
     */
    private String extraDraw(String extra, int options) {
        return switch (extra) {
            case DesignBoard.THEME -> ("Draw %d themes for the program described below: complete "
                    + "alternative looks it can switch to, such as a holiday version, added beside "
                    + "the look it has now, which stays. Keep its layout, parts and text; change the "
                    + "dressing — colours, backgrounds, artwork, decorations, type —").formatted(options);
            case DesignBoard.VARIANTS -> ("Draw %d sets of variants of one thing in the program "
                    + "described below, added beside the original, which stays: each option is one "
                    + "sheet showing the original and its variants side by side, drawn to the same "
                    + "standard and in the program's current style, so they belong in it as it is").formatted(options);
            default -> ("Draw %d animations for the program described below, added to the look it "
                    + "has now, which stays: each option is its main screen, in its current style, "
                    + "with the animation playing").formatted(options);
        };
    }

    /**
     * Where the pictures a note names are. The note says {@code [image: .forge-images/…png]}, a path
     * in the repository; a designer works beside a copy of it, so it is told to look there instead.
     */
    private String pictures(String note) {
        if (note == null || !note.contains("[image: ")) {
            return "";
        }
        return "\nThe person attached pictures, named in their words as [image: <path>]. Each is at "
                + "`reference/source/<path>` in this folder. Open and look at every one before drawing: "
                + "it shows what they mean better than the words do.";
    }

    /** For an extra, the note says what the extra is; the current look is the starting point. */
    private String extraWanted(String note) {
        String what = note == null || note.isBlank()
                ? "They have not said what it should be, so offer genuinely different ideas."
                : "What they said they want: " + note.strip();
        return what + "\nThe screenshots in `reference/` show the look the program has now. Start "
                + "from it: whatever you draw has to sit beside that look without changing it.";
    }

    /**
     * The person has a look they like and wants it varied in one respect.
     *
     * <p>Said as strongly as it is because the softer version failed: told only "I like the Sticker
     * Garage, try different car looks, the rest looks fine", designers who could not see that page
     * drew new screens from scratch, and changed everything that had been liked.
     */
    private String startingFrom(DesignOption liked, String note) {
        // Its own full stop dropped: the sentence it is put into has one.
        String pitch = liked.pitch() == null || liked.pitch().isBlank()
                ? "" : " — " + liked.pitch().strip().replaceAll("[.\\s]+$", "");
        String change = note == null || note.isBlank() ? "small things only" : note.strip();
        return """
                They have already found a look they like: "%1$s"%2$s. It is `reference/%3$s` in this \
                folder. Open it before anything else.

                Every option you draw starts from that page and keeps it: the same layout, \
                background, panels, typography, colours, buttons, text and proportions. Change only \
                what they asked to change:

                %4$s

                Make your options differ from each other in that, and only in that. Put any of them \
                beside `reference/%3$s` and the one difference should be the thing they asked for.""".formatted(
                liked.name(), pitch, LIKED_PAGE, change);
    }
}
