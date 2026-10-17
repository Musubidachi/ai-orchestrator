package dev.fmangoni.forge.brief.draft;

import dev.fmangoni.forge.spec.model.Sections;
import lombok.experimental.UtilityClass;

/**
 * Asks for a better version of one part of a spec, with the rest of it for context.
 *
 * <p>The whole description goes in every time, not just the part being changed. A goal rewritten
 * without sight of the non-goals drifts into what they exclude; notes rewritten without the goal
 * describe how to build something slightly different. The three are one document and only read
 * correctly together.
 *
 * <p>Which is also why a change to the goal or the non-goals comes back with the notes rewritten
 * too. Notes say how to build the thing — so widening the goal and leaving the notes alone produces
 * a spec that contradicts itself, and the agent that eventually builds from it has to guess which
 * half was meant. Reconciling is not an extra favour here; it is the part that keeps the spec
 * usable.
 */
@UtilityClass
public class RefineBrief {

    public String of(String title, Sections sections, String target, String feedback) {
        boolean notesFollow = !Sections.NOTES.equalsIgnoreCase(target);

        return """
                Rewrite one section of the build specification below. Reply with JSON only, no \
                preamble and no code fence.

                {"section":"the rewritten section, Markdown, no heading line",
                 "notes":"%s",
                 "what_changed":"one sentence on what you altered and why"}

                ## what to rewrite

                Section: **%s**

                %s

                ## the specification as it stands

                ### Goal
                %s

                ### Non-goals
                %s

                ### Notes
                %s

                ## how to do it

                - Rewrite only the section named above. Return its body without the `##` heading \
                line; the editor adds that back.
                - Read all three sections before you start. They are one document: a goal rewritten \
                without the non-goals in view drifts into what they exclude, and notes written \
                without the goal describe how to build something else.
                - Keep the author's intent. This is their specification and they asked for a better \
                version of a part of it, not for a different project. Where their request and the \
                existing text conflict, their request wins and you say so in `what_changed`.
                - Be concrete. "Handle errors properly" is not an improvement on anything; "reject \
                a malformed header with the line number, rather than skipping it" is.
                - Do not add scope nobody asked for, and do not quietly narrow what is there. A \
                section is not improved by being made smaller.
                %s
                ## the author's feedback on this section

                %s
                """.formatted(
                notesFollow
                        ? "the full Notes section, reconciled with your change"
                        : "",
                target,
                notesFollow
                        ? "A change here can leave the Notes describing how to build something "
                                + "else, so return the Notes as well."
                        : "You are rewriting the Notes themselves, so `notes` may be left empty.",
                blankAsPlaceholder(sections.goal()),
                blankAsPlaceholder(sections.nonGoals()),
                blankAsPlaceholder(sections.notes()),
                notesFollow ? NOTES_RULE : "",
                feedback == null || feedback.isBlank()
                        ? "(none given — improve it on your own judgement, and say what you changed)"
                        : feedback.strip());
    }

    private final String NOTES_RULE = """
            - Return the **whole** Notes section in `notes`, reconciled with your change — not a \
            patch and not a note about what to change. Carry across everything still true: the \
            stack, the libraries, the CLI shape, the formats, the edge cases. Drop only what your \
            change has made wrong, and add what it now requires.
            """;

    private String blankAsPlaceholder(String text) {
        return text == null || text.isBlank() ? "(empty)" : text.strip();
    }
}
