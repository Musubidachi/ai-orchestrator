package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.pipeline.run.RunContext;
import dev.fmangoni.forge.spec.SpecPictures;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks the person whether the screens came out like the spec's pictures.
 *
 * <p>A spec built to pictures pairs each picture with a screenshot of the same state, and the
 * report shows the two side by side. Nothing asked the person to compare them: the cross-review is
 * told to, the person's review was not, and the quick review never showed the pictures at all. This
 * puts the question among the gate's looks ({@code state.GateLooks}), which every review asks on
 * its own with nothing chosen for it, and which goes back to the agent with what the person said.
 *
 * <p>Asked before any step, so a version already past its gate is asked at its next run, and again
 * after every gate, because a full gate replaces the looks with its own. Always in the same words,
 * so a ruling on it follows it from one gate to the next, and the quick review knows its card by
 * them ({@code ui/extras/review-pictures.js} puts the pairs on that card).
 */
final class PictureLook {

    /** The question. The quick review finds its card by the words "the spec's pictures". */
    static final String QUESTION = "The screens should look like the spec's pictures: "
            + "compare each picture with its screenshot.";

    private final RunContext run;

    PictureLook(RunContext run) {
        this.run = run;
    }

    /** Asks, once, when the spec has a pictures table and the project is not finished. */
    void ask() {
        RunState state = run.state();
        if (state.getPhase().terminal() || SpecPictures.rows(run.spec().body()).isEmpty()) {
            return;
        }
        List<String> looks = state.getGateLooks() == null ? List.of() : state.getGateLooks();
        if (looks.contains(QUESTION)) {
            return;
        }
        List<String> asked = new ArrayList<>(looks);
        asked.add(QUESTION);
        state.setGateLooks(asked);
        RunStore.save(run.paths().stateFile(), state);
    }
}
