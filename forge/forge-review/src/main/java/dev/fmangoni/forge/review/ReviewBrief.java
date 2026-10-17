package dev.fmangoni.forge.review;

import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.ReviewRound;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The cross-review prompt: the other model reads the diff against the spec.
 *
 * <p>The reviewer is told the gate already passed. That matters — without it, reviewers spend their
 * attention re-litigating whether the code runs, which is the one question already answered by
 * evidence. What it cannot answer is whether the code does the right thing, quietly loses data, or
 * passes its tests for the wrong reason.
 */
@UtilityClass
public class ReviewBrief {

    /** A diff longer than this is truncated; a review of the first 200k is worth more than none. */
    private final int MAX_DIFF = 200_000;

    /**
     * Where a picked design and the builder's screenshot of the result are in the repository. The
     * same paths as {@code DesignStudio.CHOSEN_IN_REPO} and {@code PICTURE_AFTER}, which sit in a
     * module this one may not import; {@code OrchestratorTest} fails if they drift apart.
     */
    public final String PICKED_DESIGN = "artifacts/polish-design.html";
    public final String PICTURE_AFTER = "artifacts/polish-after.png";

    public String of(Spec spec, String diff) {
        return of(spec, diff, List.of());
    }

    /**
     * @param unproven claims a cheaper model already failed to find in the code, as leads
     */
    public String of(Spec spec, String diff, List<FeatureCheck> unproven) {
        return of(spec, diff, unproven, ReviewRound.first());
    }

    /**
     * @param round the blocking findings the agent was last sent to fix, to be checked by id
     */
    public String of(Spec spec, String diff, List<FeatureCheck> unproven, ReviewRound round) {
        return of(spec, diff, unproven, round, null);
    }

    /**
     * @param design the design a polish was built to, by name, or null when it was not built to one
     */
    public String of(Spec spec, String diff, List<FeatureCheck> unproven, ReviewRound round, String design) {
        return """
                Review this change. %s Reply with JSON only.

                If a tool call is refused, that is expected: this session is read-only on \
                purpose. Answer from the diff as it stands rather than stopping. A reply with no \
                findings and no text is the one useless outcome, because it cannot be told apart \
                from a reviewer that never ran.

                The project was built from the spec below and has already passed its acceptance \
                commands, so it compiles and its tests pass. You are not being asked whether it \
                runs. You are being asked whether it is *right*: does it do what the spec asked, \
                does it silently discard input it cannot handle, does it pass its tests for the \
                wrong reason, and does it hold together for a reader who has to maintain it.

                One file matters more than the rest: the class AGENTS.md asks for, which should
                read as the whole program's flow in order. Check it against what the code actually
                does. A flow that omits a step, or names one thing and does another, is worse than
                no overview at all — someone will rely on it instead of reading further, and Forge
                shows it in the report as the summary of the approach. Raise that as blocking.

                Structure counts here, and it is the thing no automated check can judge. Look for a
                class carrying several unrelated responsibilities, a method whose name describes
                only part of what it does, or a routine that has to be read twice to work out where
                one job ends and the next begins.

                Judge that by what the code is doing, not by its length. A forty-line routing table
                or a long block of prompt text is one thing done once; a fifteen-line method that
                parses, validates and writes is three. Do not raise size on its own, and do not
                propose splitting something merely because it is long — an arbitrary split satisfies
                a rule while leaving the code harder to follow, which is worse than the original.

                Reply with exactly this shape and nothing else:

                {"findings":[{"severity":"blocking","file":"src/Foo.java","summary":"one line", \
                "plain":"what someone using it would notice","stakes":"wrong result", \
                "detail":"what is wrong and why it matters"}]}

                `summary` and `detail` are for whoever fixes it. `plain` is for the person who has \
                to decide whether it matters, who did not write the code and will not read it: one \
                sentence saying what they would see go wrong while using the program, in their own \
                words, with no class, method or file names in it. "A price ending in half a cent is \
                rounded down instead of up" is one; "the rounding mode violates the contract" is \
                not. `stakes` is one of "cosmetic", "wrong result", "data loss", "security", or \
                "upkeep" for something only a person maintaining the code would ever meet.

                Use severity "blocking" only for a defect that means the project should not be \
                accepted as it stands — wrong behaviour, silent data loss, a test that cannot fail. \
                Everything else is "note". An empty findings list is a valid and useful answer; do \
                not invent problems to look thorough.
                %s%s
                ## Spec

                %s
                %s
                ## Diff

                ```diff
                %s
                ```
                """.formatted(allowed(design), recheck(round), againstTheDesign(design), spec.body(), leads(unproven),
                truncate(diff));
    }

    /**
     * What the reviewer may do besides read the diff.
     *
     * <p>Nothing, as a rule. A review against a picture is the one exception, and it has to be said
     * here, in the first lines: told "do not run any commands" up front and "open both files"
     * further down, Codex obeyed the first and never looked at the picture.
     */
    private String allowed(String design) {
        if (design == null || design.isBlank()) {
            return "Do not modify any files, and do not run any commands — the complete diff is below "
                    + "and it is all you need.";
        }
        return "Do not modify any files. Do not run any commands, except to open the two files named "
                + "under \"The look it was asked for\" below, which you must: read the picture's page, and "
                + "view the screenshot with your image tool. The diff below is all you need for the rest.";
    }

    /**
     * For a polish built to a picked design: does the result look like the picture?
     *
     * <p>Without this a restyle was reviewed only for whether it changed nothing else, or skipped as
     * appearance-only. 003 was built to "Hard-Edged Die-Cast", a design whose point was its detailed
     * cars, and came back with flat silhouettes; the colours and panels matched, so nothing objected,
     * and the person found it by running the game. The two files named are the only ones the
     * reviewer is told to open, which a read-only session can do.
     */
    private String againstTheDesign(String design) {
        if (design == null || design.isBlank()) {
            return "";
        }
        return """

                ## The look it was asked for

                This change restyles the program to a design the person picked: "%1$s". The picture \
                is `%2$s` and the builder's screenshot of the result is `%3$s`. Open both — reading \
                these two files is allowed — and compare them the way the person will when they \
                open the program.

                The artwork is part of the look: characters, vehicles, icons and illustrations must \
                be drawn at the picture's level of detail, not replaced with simpler shapes, even \
                where the program has to recolour them. So are its badges, labels, ribbons and \
                decorations, the shapes of its panels and buttons, its colours and its type. Raise \
                each thing the picture shows that the result leaves out or visibly simplifies as \
                blocking, with `stakes` "cosmetic", and say in `plain` what the person would see \
                missing. Something the picture shows that the program does not actually have is not \
                a defect, and nor are small differences of spacing or wording.

                If `%3$s` is missing, empty or shows something other than the program's main \
                screen, raise that as blocking too: without it, nobody has looked.

                Say in your reply whether you did this: add `"compared_with_design": true` beside \
                `findings` once you have opened both files and compared them, and \
                `"compared_with_design": false` if you could not open either. A review that did not \
                compare is not accepted as one, so do not write true without having looked.
                """.formatted(design, PICKED_DESIGN, PICTURE_AFTER);
    }

    /** The files the findings are in, as they stand now, so a fix can be seen whatever the diff kept. */
    private String files(ReviewRound round) {
        if (round.files() == null || round.files().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n## Those files as they stand now\n");
        round.files().forEach((path, text) -> out.append("\n### ").append(path).append("\n\n```\n")
                .append(text).append(text.endsWith("\n") ? "" : "\n").append("```\n"));
        return out.toString();
    }

    /**
     * The findings from the round before, to be ruled on by id before anything new is raised.
     *
     * <p>Every blocking finding has to be fixed before a project can finish, so whether the last
     * one was is the first question — and it was the one question no review asked. Each review
     * read the change afresh and raised whatever it saw, which reworded the same unfixed defect
     * round after round: division rounding, the order of DOCX text around tables, auto-bounds
     * dropping parts of the subject, each "sent back" and each back again under a new name.
     */
    private String recheck(ReviewRound round) {
        if (!round.rechecking() && round.ruledFine().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (round.rechecking()) {
            out.append("""

                    ## Findings from the last review — check each one first

                    These were raised as blocking last time and the builder was sent back to fix \
                    them; its account of what it did is under each. Decide whether each is actually \
                    fixed from the file it is in, as it stands now: those files are given in full \
                    below, because the diff may be cut short before reaching them. Report every one in `rechecks`, by its id, with status \
                    `fixed`, `still_present` or `withdrawn` (withdrawn means you now think the \
                    original finding was wrong — say why). If one is still present, say in `note` \
                    specifically why the change does not answer it; that sentence is what the \
                    builder is given next. Do not raise any of these again in `findings`.

                    """);
            for (Finding finding : round.previous()) {
                out.append("- id `").append(finding.getId()).append("`: ");
                if (finding.getFile() != null && !finding.getFile().isBlank()) {
                    out.append(finding.getFile()).append(" — ");
                }
                out.append(finding.getSummary()).append('\n');
                if (finding.getDetail() != null && !finding.getDetail().isBlank()) {
                    out.append("  What was wrong: ").append(finding.getDetail()).append('\n');
                }
                String answer = round.answers().get(finding.getId());
                out.append("  The builder says: ")
                        .append(answer == null || answer.isBlank() ? "(nothing about this one)" : answer)
                        .append('\n');
            }
            out.append(files(round));
            out.append("""

                    Reply with both lists in one object:

                    {"rechecks":[{"id":"blocking-1a2b3c4d","status":"still_present","note":"why"}], \
                    "findings":[...]}
                    """);
        }
        if (!round.ruledFine().isEmpty()) {
            out.append("""

                    ## Already ruled acceptable by the person this is for

                    They read these and decided the project may stand with them. Do not raise any \
                    of them again, in any wording:

                    """);
            round.ruledFine().forEach(text -> out.append("- ").append(text).append('\n'));
        }
        return out.toString();
    }

    /**
     * What the cheap pass could not find, handed over as leads rather than as findings.
     *
     * <p>A small model reading for one feature is good at "I could not find this" and poor at
     * deciding what it means, which is the division of labour here: it says where to look, and the
     * model that can judge decides whether it matters. Stated as unconfirmed on purpose — treated
     * as fact, a weak model's miss becomes a blocking finding nobody checked.
     */
    private String leads(List<FeatureCheck> unproven) {
        if (unproven == null || unproven.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("""

                ## Claims a first pass could not confirm

                A smaller model read the code for each feature this project says it has and could \
                not find these. It may simply have missed them. Check them yourself, and raise a \
                finding only if you agree the feature is missing, a stub, or materially different \
                from what is claimed.

                """);
        for (FeatureCheck check : unproven) {
            out.append("- ").append(check.getFeature());
            if (check.getWhy() != null && !check.getWhy().isBlank()) {
                out.append(" — ").append(check.getWhy());
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }

    private String truncate(String diff) {
        if (diff.length() <= MAX_DIFF) {
            return diff;
        }
        return diff.substring(0, MAX_DIFF) + "\n...[diff truncated at " + MAX_DIFF + " characters]...";
    }
}
