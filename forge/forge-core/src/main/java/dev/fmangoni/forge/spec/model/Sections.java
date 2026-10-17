package dev.fmangoni.forge.spec.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A spec's prose, split into the three parts a person actually edits.
 *
 * <p>On disk the description is one Markdown block handed to the agent verbatim, and it stays that
 * way — the file is what an agent reads as {@code SPEC.md}, and turning it into structured fields
 * would mean maintaining a format nobody else understands. This splits it for the editor and puts
 * it back together afterwards.
 *
 * <p>Anything that is not one of the three known headings is kept in {@link #extra()} and written
 * back out after them. A spec is someone's own writing; an editor that silently dropped a section
 * it did not recognise would be worse than one that could not split at all.
 *
 * @param goal      what the program does and why
 * @param nonGoals  what it must not grow into
 * @param notes     how it should be built — the part that has to stay consistent with the other two
 * @param extra     headings this does not know about, preserved verbatim
 */
public record Sections(String goal, String nonGoals, String notes, String extra) {

    public static final String GOAL = "Goal";
    public static final String NON_GOALS = "Non-goals";
    public static final String NOTES = "Notes";

    /** The three a caller may ask to have rewritten. */
    public static List<String> refinable() {
        return List.of(GOAL, NON_GOALS, NOTES);
    }

    public static Sections of(String body) {
        if (body == null || body.isBlank()) {
            return new Sections("", "", "", "");
        }
        StringBuilder goal = new StringBuilder();
        StringBuilder nonGoals = new StringBuilder();
        StringBuilder notes = new StringBuilder();
        StringBuilder extra = new StringBuilder();
        StringBuilder current = extra;

        for (String line : body.split("\\R", -1)) {
            StringBuilder next = headingTarget(line, goal, nonGoals, notes, extra);
            if (next != null) {
                current = next;
                // The heading itself is re-emitted by toBody, except for an unknown one, which has
                // to keep its own heading or it would merge into whatever came before it.
                if (next == extra) {
                    current.append(line).append('\n');
                }
                continue;
            }
            current.append(line).append('\n');
        }
        return new Sections(goal.toString().strip(), nonGoals.toString().strip(),
                notes.toString().strip(), extra.toString().strip());
    }

    /**
     * Which buffer a heading line switches to, or null when the line is not a heading.
     *
     * <p>Matches on the heading text rather than the exact string, so {@code ## Non-Goals} and
     * {@code ## non-goals} land where the author plainly meant them to.
     */
    private static StringBuilder headingTarget(String line, StringBuilder goal,
                                               StringBuilder nonGoals, StringBuilder notes,
                                               StringBuilder extra) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("#")) {
            return null;
        }
        String title = trimmed.replaceAll("^#+", "").strip().toLowerCase(Locale.ROOT);
        return switch (title) {
            case "goal", "goals" -> goal;
            case "non-goals", "non goals", "nongoals", "non-goal" -> nonGoals;
            case "notes", "note" -> notes;
            default -> extra;
        };
    }

    /** The Markdown an agent reads, with the headings back in the order a reader expects. */
    public String toBody() {
        List<String> parts = new ArrayList<>();
        append(parts, GOAL, goal);
        append(parts, NON_GOALS, nonGoals);
        append(parts, NOTES, notes);
        if (extra != null && !extra.isBlank()) {
            parts.add(extra.strip());
        }
        return String.join("\n\n", parts) + "\n";
    }

    private void append(List<String> parts, String heading, String text) {
        if (text != null && !text.isBlank()) {
            parts.add("## " + heading + "\n" + text.strip());
        }
    }

    /** This, with one section replaced. Unknown names are ignored rather than guessed at. */
    public Sections with(String heading, String text) {
        String value = text == null ? "" : text.strip();
        return switch (heading == null ? "" : heading.toLowerCase(Locale.ROOT)) {
            case "goal" -> new Sections(value, nonGoals, notes, extra);
            case "non-goals" -> new Sections(goal, value, notes, extra);
            case "notes" -> new Sections(goal, nonGoals, value, extra);
            default -> this;
        };
    }
}
