package dev.fmangoni.forge.agent.vendor.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * The Antigravity command line, kept separate from the code that runs it.
 *
 * <p>Verified against agy 1.2.2 by running it, including the part that mattered most: a turn in
 * {@code --mode plan} asked to write two files called {@code write_to_file} three times and left
 * the workspace untouched. Precisely: it wrote them under its own scratch directory instead, so
 * plan mode is containment rather than abstinence — the reviewer cannot reach the code it is
 * judging, which is the property that matters, but it is not mute. Checked by running it rather
 * than assumed, because the equivalent flag on the Gemini CLI was accepted and silently ignored.
 *
 * <p>Three things were learned by being wrong about them:
 *
 * <ul>
 *   <li><b>The prompt cannot be piped in text mode.</b> {@code -p} takes its prompt as the flag's
 *       own value, and an empty one is rejected outright with "empty prompt". Windows caps a
 *       command line near 32k and a review brief carries a spec plus a diff — {@code REVIEW_DIFF_CAP}
 *       alone is 60k — so passing it as an argument is not an option that merely looks untidy; it
 *       is one that fails.
 *   <li><b>{@code --input-format stream-json} is the way in.</b> One NDJSON message on stdin, which
 *       has no length limit. It requires {@code --output-format stream-json}, so the reply arrives
 *       as a stream of events rather than one object — which is the better half of the trade,
 *       because it gives a transcript that fills as the turn runs.
 *   <li><b>{@code --print-timeout} defaults to five minutes.</b> Forge sets its own per-turn cap,
 *       routinely longer than that, and leaving this at the default would cut a long review off
 *       part-way and report it as a hang.
 * </ul>
 *
 * <p>There is no working-directory flag; the process's own directory is the workspace, which
 * {@code CliRunner} sets. That is what keeps an agent inside {@code runs/NNN/repo}.
 */
@UtilityClass
public class AntigravityArgs {

    /**
     * {@code --print} with an empty attached value: present, selecting print mode, carrying nothing.
     *
     * <p>Three shapes were tried against agy 1.2.2 and two are refused:
     *
     * <ul>
     *   <li>Bare {@code -p} takes the <em>next argument</em> as its prompt — "-p took
     *       \"--output-format\" as its prompt, so the intended prompt was left as an argument and
     *       ignored". A separated value is not optional; it is a trap.
     *   <li>{@code --print=<anything>} alongside {@code --input-format stream-json} is rejected
     *       outright: "reads prompts from stdin, so a prompt given on the command line would be
     *       ignored". The CLI refusing an ambiguous instruction rather than quietly picking one is
     *       the behaviour to want, and it is what caught this.
     *   <li>{@code --print=} with nothing after the equals is accepted, and stdin is read.
     * </ul>
     */
    private final String PRINT_FLAG = "--print=";

    /**
     * @param readOnly  run in plan mode, where tool calls that would write are refused
     * @param resume    a conversation to continue, or {@code null} for a new one
     * @param requested the model this turn asked for
     * @param fallback  the run-wide model, used when the turn asked for nothing
     * @param cap       Forge's own timeout for this turn, so the CLI's does not fire first
     */
    public List<String> of(boolean readOnly, String resume,
                           String requested, String fallback, Duration cap) {
        List<String> args = new ArrayList<>();

        args.add(PRINT_FLAG);
        args.add("--input-format");
        args.add("stream-json");
        args.add("--output-format");
        args.add("stream-json");

        // plan is read-only; accept-edits is what an unattended build needs, since there is nobody
        // to answer a permission prompt. The skip-permissions flag is deliberately paired only with
        // the writing mode: a reviewer has no business auto-approving anything.
        args.add("--mode");
        args.add(readOnly ? "plan" : "accept-edits");
        if (!readOnly) {
            args.add("--dangerously-skip-permissions");
        }

        if (resume != null && !resume.isBlank()) {
            args.add("--conversation");
            args.add(resume);
        }

        String model = chosen(requested, fallback);
        if (model != null) {
            args.add("--model");
            args.add(model);
        }

        // Longer than Forge's own cap would be pointless and shorter would pre-empt it, so they
        // are the same number. CliRunner still owns the kill; this only stops the CLI giving up
        // first and reporting a timeout Forge has no context for.
        if (cap != null) {
            args.add("--print-timeout");
            args.add(cap.toSeconds() + "s");
        }
        return args;
    }

    /**
     * The brief, as the one NDJSON message the CLI reads from stdin.
     *
     * <p>{@code message.content} as a plain string is accepted, as is the nested Claude-shaped
     * form; the flat one is used because it is the one with nothing in it to get wrong.
     */
    public String stdinMessage(String brief) {
        try {
            return new ObjectMapper().writeValueAsString(
                    Map.of("event", "user",
                            "message", Map.of("content", brief == null ? "" : brief)))
                    + System.lineSeparator();
        } catch (JsonProcessingException e) {
            // A brief is a String going into a two-field map; there is no shape here that Jackson
            // can fail on, and pretending otherwise would mean a catch block nothing can test.
            throw new IllegalStateException("cannot encode the brief", e);
        }
    }

    /** The turn's own choice wins over the run-wide default; blank means neither was set. */
    private String chosen(String requested, String fallback) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
