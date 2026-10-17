package dev.fmangoni.forge.brief.repair;

import dev.fmangoni.forge.brief.TimeContract;
import dev.fmangoni.forge.review.model.Finding;
import java.time.Duration;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The prompt after a cross-review found blocking defects in code that already passes its gate.
 *
 * <p>Every blocking finding has to be fixed before the project can finish. It used to be otherwise:
 * the builder was invited to push back on a reviewer who could not run the code, was given one
 * turn, and the project went to DONE whatever the next review said. What that produced here was
 * the same defects coming back round after round under new wording — division rounding, DOCX text
 * reordered around tables, auto-bounds dropping parts of the subject — each sent back by hand and
 * each still present.
 *
 * <p>A reviewer can still be wrong, so disputing is allowed, but only with evidence, and it is the
 * next reviewer rather than the builder who decides whether the dispute stands. A finding still
 * present after its last attempt goes to a person.
 */
@UtilityClass
public class ReviewRepairBrief {

    /**
     * @param blocking    the outstanding findings, each with its id and attempts counted
     * @param maxAttempts how many attempts a finding gets before it goes to a person
     */
    public String of(List<Finding> blocking, int maxAttempts, Duration cap) {
        return """
                A second model reviewed your work and raised the blocking findings below. Your \
                acceptance commands already pass, so this is about correctness rather than whether \
                the code runs.

                Every one of these must be fixed before this project can be finished. Each gets \
                %d attempts; one still present after its last goes to the person this is for, with \
                your answer beside it.

                %s
                For each finding:

                - Fix the defect itself, not the symptom the reviewer happened to describe, and add \
                a test that fails without your fix. A fix with no test that would catch its return \
                is how several of these came back.
                - If you are certain a finding is wrong, you may dispute it — but only with \
                evidence: a test that exercises exactly the behaviour the finding says is broken, \
                and shows it is right. The next reviewer reads your test and decides; a dispute it \
                does not accept counts as unfixed.
                - Do not weaken an acceptance command, a test, or an assertion to make a finding go \
                away.

                Then make sure the acceptance commands still pass, and add this to your handoff, \
                one entry per finding, by its id:

                "review_findings": [
                  {"id": "blocking-1a2b3c4d", "action": "fixed", \
                "how": "what you changed, in a sentence", "test": "path/to/Test::name"}
                ]

                `action` is `fixed` or `disputed`. The next reviewer is shown exactly this.

                %s""".formatted(maxAttempts, render(blocking, maxAttempts), TimeContract.of(cap));
    }

    private String render(List<Finding> blocking, int maxAttempts) {
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (Finding finding : blocking) {
            out.append(n++).append(". ");
            if (finding.getId() != null && !finding.getId().isBlank()) {
                out.append("[").append(finding.getId()).append("] ");
            }
            if (finding.getFile() != null && !finding.getFile().isBlank()) {
                out.append(finding.getFile()).append(" — ");
            }
            out.append(finding.getSummary() == null ? "(no summary)" : finding.getSummary()).append('\n');
            if (finding.getDetail() != null && !finding.getDetail().isBlank()) {
                out.append("   ").append(finding.getDetail()).append('\n');
            }
            if (finding.getStillPresent() != null && !finding.getStillPresent().isBlank()) {
                out.append("   You were sent back for this before, and the reviewer checked your change: ")
                        .append(finding.getStillPresent()).append('\n');
            }
            out.append("   Attempt ").append(Math.max(1, finding.getAttempts())).append(" of ")
                    .append(maxAttempts).append(".\n\n");
        }
        return out.toString();
    }
}
