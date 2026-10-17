package dev.fmangoni.forge.jobs.polish;

import dev.fmangoni.forge.design.DesignBoard;
import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.DesignStudio;
import dev.fmangoni.forge.gate.LookCheck;
import lombok.experimental.UtilityClass;

/**
 * What adding an extra asks for: a new look beside the one the program has, never instead of it.
 *
 * <p>A theme, variants of one thing, or an animation, each built as a pack the program can switch to.
 * The first extra a project gets also makes the place packs go: the current look moved behind one
 * pack interface as the default pack, and the default look saved by the acceptance suite so
 * {@link LookCheck} can hold it still from then on. Every later extra only adds a pack.
 *
 * <p>Like {@link PolishBrief}, written as the person's own request and delivered the same way, and
 * most of it is about what must not move.
 */
@UtilityClass
public class ExtraBrief {

    /**
     * @param kind    {@link DesignBoard#THEME}, {@link DesignBoard#VARIANTS} or {@link DesignBoard#ANIMATION}
     * @param pack    what the new pack is called
     * @param note    what the person particularly wants, or blank
     * @param chosen  the design they picked for it, or null to leave its look to the agent
     * @param hasSlot whether the program already has a place for packs
     */
    public String request(String kind, String pack, String note, DesignOption chosen, boolean hasSlot) {
        String wanted = note == null || note.isBlank() ? "" : "\nWhat I particularly want: " + note.strip() + "\n";
        return """
                Add "%2$s" to how this program looks: %1$s. It goes beside the look the program \
                has now, which stays exactly as it is.
                %3$s%4$s
                %5$s

                Keep it the same program:
                - Do not change what it does, its rules, its difficulty, its timing or its \
                controls, and do not remove anything.
                - Do not weaken or delete any test or acceptance check.
                - Stay inside the spec's non-goals and the house rules on dependencies. Nothing \
                fetched from the network when it runs.
                - Keep it usable in every pack: readable contrast, a visible keyboard focus, \
                nothing that only works by colour.

                Capture the program before you start as `%6$s`, and when you are done as `%7$s` \
                with the new pack switched on, so the two can be compared. In your handoff, list \
                "%2$s" under `features`, with what a recording of it should show, so the person \
                can approve it and the gate will film it.""".formatted(
                what(kind), pack, look(chosen), wanted, slot(kind, hasSlot),
                DesignStudio.PICTURE_BEFORE, DesignStudio.PICTURE_AFTER);
    }

    private String what(String kind) {
        return switch (kind) {
            case DesignBoard.THEME -> "a theme, a whole alternative look applied together (colours, "
                    + "backgrounds, artwork, decorations and type)";
            case DesignBoard.VARIANTS -> "a set of variants, alternative versions of one thing in it, "
                    + "each drawn to the same standard as the original";
            default -> "an animation, new motion in the look it has";
        };
    }

    private String look(DesignOption chosen) {
        if (chosen == null) {
            return "\nWhat it looks like is left to you: make it belong beside the current look.\n";
        }
        // Its own full stop dropped: the sentence it is put into has one.
        String pitch = chosen.pitch() == null || chosen.pitch().isBlank()
                ? "" : " — " + chosen.pitch().strip().replaceAll("[.\\s]+$", "");
        return """

                I was shown designs for it and chose one: "%s"%s. The picture is in this repository \
                at `%s`. Make the new pack look like that picture, at its level of detail: draw its \
                characters, vehicles, icons and decorations as it draws them, not as simpler \
                shapes, and where the program recolours or swaps a drawing, make the detailed one \
                recolourable, reusing the picture's SVG if that is easiest. Where it shows something \
                the program does not have, the program is right. Leave the file where it is. The \
                result is compared with the picture, from your after screenshot.
                """.formatted(chosen.name(), pitch, DesignStudio.CHOSEN_IN_REPO);
    }

    private String slot(String kind, boolean hasSlot) {
        String switching = switch (kind) {
            case DesignBoard.THEME -> "a setting that switches the whole look, remembered between visits";
            case DesignBoard.VARIANTS -> "each variant addressable by its own id, so the program can show "
                    + "whichever one the player has";
            default -> "named animations the program can play, each one off when the player prefers "
                    + "reduced motion, and none of them holding up input or the game's timing";
        };
        if (hasSlot) {
            return """
                    The program already has a place for packs: add this one there, with %s.

                    The default look is held still: the acceptance suite saves it as `%s`, and Forge \
                    compares that picture with a reference at every gate and fails the gate if it \
                    changed. Leave the default look exactly as it is.""".formatted(switching, LookCheck.DEFAULT_LOOK);
        }
        return """
                The program has no place for packs yet, so make one first, as part of this:
                - The look it has now becomes the default pack. Put its colours, artwork and motion \
                behind one pack interface — CSS custom properties, a registry of drawings, whatever \
                fits the stack — so another pack can replace them without touching the program's \
                logic. The default pack must look exactly as the program does now.
                - A way to switch: %s. How a player gets a pack — a menu, a date, a purchase — is \
                whatever the spec says; if it says nothing, a simple picker in the settings.
                - Make the acceptance suite save the default look's main screen, with no pack \
                switched on, at a fixed screen size, as `%s` on every run. Forge keeps the first one \
                as a reference and fails the gate from then on if the default look changes.

                Then add the new pack to it.""".formatted(switching, LookCheck.DEFAULT_LOOK);
    }
}
