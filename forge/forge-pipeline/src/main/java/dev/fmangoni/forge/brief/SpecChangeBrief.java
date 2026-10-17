package dev.fmangoni.forge.brief;

import lombok.experimental.UtilityClass;

/**
 * Tells the agent that what the project is for has changed since its last session, and how.
 *
 * <p>Put in front of the turn's own brief, because it outranks it. An agent carrying on from its
 * own plan, or from a conversation that remembers the old spec, will otherwise defend the old one:
 * 003's did, six sessions running, against a change the person had just made.
 */
@UtilityClass
public class SpecChangeBrief {

    /** Past this the diff is a rewrite, and the agent is better pointed at the file than shown it. */
    private static final int MAX_DIFF = 12_000;

    /**
     * @param file the copy that changed, {@code SPEC.md} or {@code IMPROVEMENT.md}
     * @param diff the change to it, as a unified diff
     */
    public String of(String file, String diff) {
        String shown = diff.length() <= MAX_DIFF ? diff.strip()
                : diff.substring(0, MAX_DIFF).strip() + "\n… (the rest is in the file)";
        return """
                ## The spec has changed

                What this project is for has changed since your last session. `%s` in the repository \
                is now the current version, and it is what the project is judged against: where it \
                contradicts a decision you made earlier, a step in your plan, or a finding you acted \
                on, the spec wins. Read the change, then the whole file again.

                ```diff
                %s
                ```

                Build what is newly asked for, with its tests and, for a feature, its recording. Take \
                out what is now ruled out. Say in your handoff what you changed because of this.

                """.formatted(file, shown);
    }
}
