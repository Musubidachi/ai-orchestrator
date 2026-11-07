package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.pipeline.run.PersonHold;
import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.spec.SpecPictures;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.util.List;

/**
 * Holds a project for its person before any step when its spec's pictures are not ready.
 *
 * <p>A picture the spec names that is not in the repository, or a screenshot of one that the
 * evidence list does not ask for, is a mistake in the spec, and no agent can mend it: the
 * pictures folder is the person's, and the spec is theirs. Found by a turn, it cost that turn and
 * then a repair round. Found here, before anything runs, it costs nothing, and the card says
 * which picture.
 *
 * <p>Pressing Start lifts the hold as every hold is lifted, and the next start looks again.
 */
final class MissingPictures {

    private final RunContext run;

    MissingPictures(RunContext run) {
        this.run = run;
    }

    /** Holds the project, saying why on its card, if its pictures are not ready. */
    void holdIfAny() {
        RunState state = run.state();
        Phase phase = state.getPhase();
        if (phase.terminal() || phase == Phase.SUSPENDED || state.getNeedsPerson() != null) {
            return;
        }
        List<String> problems = SpecPictures.problems(run.spec(), run.paths().repo());
        if (problems.isEmpty()) {
            return;
        }
        PersonHold.hold(state, "the spec's pictures are not ready: " + String.join("; ", problems));
        RunStore.save(run.paths().stateFile(), state);
    }
}
