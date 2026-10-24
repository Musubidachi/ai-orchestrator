package dev.fmangoni.forge.jobs.polish;

import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.DesignStudio;
import lombok.experimental.UtilityClass;

/**
 * What the Polish button asks for: the same program, looking finished.
 *
 * <p>Written as the person's own request, because that is what it is and that is how it is
 * delivered — through {@code FeedbackBrief}, which tells the agent these are the words of whoever
 * the program is for. A general instruction to "improve the graphics" is an invitation to redesign,
 * so most of this is about what must not move: what the program does, what its tests assert, and
 * what the spec ruled out.
 *
 * <p>When the person picked a design, that is what is asked for, and the mock-up is in the
 * repository for the agent to open. It is described as a picture to match and not as code to
 * paste, because a mock-up is a still page somebody drew of the main screen: it has no behaviour,
 * and it may show the screen slightly wrong.
 *
 * <p>It asks for a picture taken before and after. Nothing checks them; the report shows them.
 */
@UtilityClass
public class PolishBrief {

    /** How a note names a picture the person attached; the same marker {@code DesignBrief} reads. */
    private final String ATTACHED_PICTURE = "[image: ";

    /**
     * @param note   what the person particularly wants, or blank
     * @param chosen the design they picked, or null to leave the look to the agent
     */
    public String request(String note, DesignOption chosen) {
        String wanted = note == null || note.isBlank()
                ? ""
                : "\nWhat I particularly want: " + note.strip() + "\n";
        return """
                Polish how this looks. It works, and this request is about its appearance and \
                nothing else.

                %s%s
                Keep it the same program:
                - Do not change what it does, its rules, its difficulty or its controls, and do \
                not add or remove features.
                - Do not weaken or delete any test or acceptance check. If a test asserts on a \
                colour, a size or a position you want to change, work within what it allows, or \
                say in your handoff which assertion stands in the way. Do not edit it.
                - Stay inside the spec's non-goals and the house rules on dependencies. Nothing \
                fetched from the network when it runs, and no new dependency unless the look \
                cannot be had without it.
                - Keep it usable: readable contrast, a visible keyboard focus, nothing that only \
                works by colour.

                Look at it before and after. Capture the running program before you start as \
                `%s` and again when you are done as `%s`, and compare the two yourself. If you \
                cannot see the improvement, neither will I.
                %s
                If there is nothing here that a person looks at — a library, or a tool with no \
                output worth styling — say so in your handoff and change nothing, rather than \
                inventing something to decorate. For a command-line tool, what it prints and any \
                report or page it generates are what there is to polish.""".formatted(
                look(chosen), wanted, DesignStudio.PICTURE_BEFORE, DesignStudio.PICTURE_AFTER, screens(note));
    }

    /**
     * When the note names pictures the person attached, a screenshot of every screen they show.
     *
     * <p>One screenshot of the main screen is all a picked design needs, since that is all it draws.
     * Pictures of seven screens need seven, or the reviewer holding each picture up to the result
     * has nothing to hold it against for six of them. The pictures outrank the design because the
     * design was drawn from them.
     */
    private String screens(String note) {
        if (note == null || !note.contains(ATTACHED_PICTURE)) {
            return "";
        }
        return """

                My pictures are the look I want, and where one disagrees with the design, the \
                picture wins. Match each one as a still picture: motion it only implies, such as \
                a moving camera or blur, is not asked for, and the program's own animation stays. \
                When you are done, also capture every screen my pictures show, each as \
                `artifacts/polish-after-<screen>.png` named for the screen, such as \
                `artifacts/polish-after-garage.png`. They are compared with my pictures one by one.
                """;
    }

    private String look(DesignOption chosen) {
        if (chosen == null) {
            return """
                    Improve the graphics and the visual presentation of what a person actually sees \
                    when they use it: layout and spacing, typography, colour and contrast, the \
                    artwork or shapes it draws, the motion and feedback when something happens, and \
                    how it holds up at the window and screen sizes it is meant for. It should look \
                    finished and deliberate rather than like a prototype.
                    """;
        }
        String pitch = chosen.pitch() == null || chosen.pitch().isBlank() ? "" : " — " + chosen.pitch().strip();
        return """
                I was shown several designs and chose one: "%s"%s. The picture of it is in this \
                repository at `%s`. Open it, and make the program look like that: its colours, its \
                type, its spacing, its shapes and its mood, on every screen and state the program \
                has, not only the one in the picture.

                It is a picture of where to get to, not code to paste in. It is a still page \
                somebody drew of the main screen: it has no behaviour, and where it shows \
                something the program does not have, or leaves out something it does, the program \
                is right and the picture is wrong. Leave the file where it is.

                Its artwork is part of the look, and the part most easily lost. Draw the program's \
                pictures — characters, vehicles, icons, illustrations — at the picture's level of \
                detail, and keep its badges, labels and decorations. Where the program changes a \
                drawing the picture shows in one state, such as an item the player recolours, make \
                that detailed drawing recolourable, reusing the picture's SVG if that is easiest. \
                Do not replace it with a simpler shape. The result is compared with the picture, \
                from your after screenshot, by a reviewer who will send back what is missing.
                """.formatted(chosen.name(), pitch, DesignStudio.CHOSEN_IN_REPO);
    }
}
