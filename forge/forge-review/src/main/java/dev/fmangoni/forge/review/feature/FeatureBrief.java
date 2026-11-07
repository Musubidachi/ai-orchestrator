package dev.fmangoni.forge.review.feature;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.spec.model.Spec;
import lombok.experimental.UtilityClass;

/**
 * The cheap pass's prompt: one claimed feature, one question.
 *
 * <p>Deliberately narrow. This runs on the smallest model the vendor has, once per feature, and a
 * small model does far better at "find where this happens and say whether it really does" than at
 * judging a whole change. The reading it produces is then handed to the one expensive review as
 * leads, which is what makes that review cheaper than it used to be: it arrives knowing which
 * claims are already in doubt.
 */
@UtilityClass
class FeatureBrief {

    /** The reply that says the reviewer never got to read the code; it is not a verdict on the claim. */
    static final String UNREAD = "unread";

    String of(PlanFeature feature, Spec spec, AgentKind reader) {
        return """
                In this repository, check one claim and answer in JSON.

                The project "%s" says it has this feature:

                  Feature: %s
                  What it should show: %s
                %s
                Read the code that would implement it. %s

                Answer only this question: is the feature really there, in the code, doing what the \
                claim says? Not whether it is written well, and not whether you would have built it \
                differently.

                Reply with exactly this shape and nothing else:

                {"verdict":"holds","why":"one line, naming the file that does it"}

                Use "holds" when you found the code that does it. Use "unproven" when you could not \
                find it, when what you found does something materially different, or when it is \
                only a stub, a placeholder or a hard-coded value pretending to be the real thing. \
                In either case name the file you looked at in "why", so the next reader can start \
                where you stopped. Use "%s" only when you could not open the code at all, and say \
                why: that is not a verdict on the claim, and another reviewer will be asked.
                """.formatted(spec.title(), feature.getName(), blankIfNull(feature.getDemo()), whatWasAsked(spec),
                howToRead(reader), UNREAD);
    }

    /**
     * How this vendor can read files in a read-only session, which differs between them.
     *
     * <p>Claude and Antigravity have file tools and no command runner: a command is refused, and
     * Antigravity ends the session on a refusal before answering. Codex has no file tools at all; it
     * reads by running commands, which its read-only sandbox lets run. Telling Codex not to run
     * any command left it with no way to open a file, as on 008.2, and a reviewer that cannot look
     * can only call a claim unproven.
     */
    private String howToRead(AgentKind reader) {
        return switch (reader) {
            case CODEX -> "Read files and search with read-only commands, such as rg, ls and Get-Content. "
                    + "Do not change anything: this session is read-only, and a command that writes is refused.";
            case CLAUDE, ANTIGRAVITY -> "Open files with your file-reading and search tools only. Do not change "
                    + "anything and do not run any command, not even to list or search: this session is "
                    + "read-only, a command is refused, and a refusal can end the session before you have answered.";
        };
    }

    /**
     * Which file says what was asked for.
     *
     * <p>An enhancement's repository holds the original {@code SPEC.md} as well as the change in
     * {@code IMPROVEMENT.md}, and the original's non-goals often rule out exactly what the change
     * adds. A checker that read only {@code SPEC.md} called 003.3's main new feature out of scope, because
     * the racing game it improved had said "no cash economy".
     */
    private String whatWasAsked(Spec spec) {
        if (!spec.isImprovement()) {
            return "";
        }
        return """

                This project is a change to an earlier one. What was asked for is in IMPROVEMENT.md; \
                SPEC.md describes the project it changes. Where they differ, IMPROVEMENT.md wins: a \
                feature SPEC.md's non-goals rule out may be exactly what IMPROVEMENT.md asks for.
                """;
    }

    private String blankIfNull(String demo) {
        return demo == null || demo.isBlank() ? "(the handoff did not say)" : demo;
    }
}
