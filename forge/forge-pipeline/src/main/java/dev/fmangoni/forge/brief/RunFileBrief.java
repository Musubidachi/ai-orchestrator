package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.gate.RunFile;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * A few lines added to any turn's prompt while the project has no run file the gate will accept.
 *
 * <p>The rule is in the house rules, which are rewritten into the workspace before every turn. That
 * reaches a project being started. It does not reliably reach one already under way: its agent is
 * resuming a conversation, was handed a brief about something else, and has no reason to read the
 * rules again. Left to the gate, such a project finds out by failing it, and pays a repair round
 * and a session to learn about one small file. Told here, it adds the file in the turn it was
 * going to have anyway.
 *
 * <p>Asks the gate's own question ({@link RunFile#problem}), so a run file the gate would refuse
 * never silences the note.
 */
@UtilityClass
public class RunFileBrief {

    /** The note, or nothing once the project has a run file the gate will accept. */
    public String ifOwed(Path repo) {
        return RunFile.problem(repo).map(problem -> """


                ## Also owed in this turn: a working `%1$s`

                %2$s Every project needs one in the repository root — the file the person this \
                was built for double-clicks to try it — and the gate does not pass without it. Put \
                that right in this turn, alongside the work above. AGENTS.md, under "A way to run \
                it", says what it has to do. Do not add it to the acceptance commands.
                """.formatted(RunFile.NAME, problem)).orElse("");
    }
}
