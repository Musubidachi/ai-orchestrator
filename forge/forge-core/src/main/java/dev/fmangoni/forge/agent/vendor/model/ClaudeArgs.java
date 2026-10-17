package dev.fmangoni.forge.agent.vendor.model;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The Claude Code command line, kept separate from the code that runs it.
 *
 * <p>This is where the assumptions about another program live, and they expire: {@code --max-turns}
 * was removed after it turned out not to exist in 2.1.263. Isolating the translation from task to
 * argv means those assumptions can be read in one place and asserted on without spawning anything.
 *
 * <p>Verified against Claude Code 2.1.263.
 */
@UtilityClass
public class ClaudeArgs {

    /**
     * @param readOnly  drop the tools that run commands or code
     * @param resume    an existing session to continue, or {@code null} for a new one
     * @param session   the id to assign when starting fresh; ignored when resuming
     * @param requested the model this turn asked for
     * @param fallback  the run-wide model, used when the turn asked for nothing
     */
    public List<String> of(boolean readOnly, String resume, String session,
                           String requested, String fallback) {
        List<String> args = new ArrayList<>(List.of(
                "-p",
                "--output-format", "stream-json",
                "--verbose",
                // No MCP servers but the ones named here, and none are. Without it a turn inherits
                // every server the person has configured for their own use — mail, chat, source
                // hosting — and bypassPermissions lets the agent call any of them unasked, which
                // is how "never post or publish" would be broken without anyone deciding to.
                // Checked against Claude Code 2.1.283's --help.
                "--strict-mcp-config"));

        // Both of these mean "do not stop and ask", but they are not interchangeable and the CLI
        // refuses the combination outright: "bypassPermissions not supported in restricted mode",
        // exit 1, nothing written. That killed every Claude review turn and every suggestion
        // silently, because an empty reply reads downstream as a model that said nothing.
        //
        // bypassPermissions is what makes an unattended build possible — without it the agent
        // stops at the first Bash call and waits for a human who is not watching. A restricted
        // turn has no Bash to stop at, so dontAsk is the equivalent there.
        args.add("--permission-mode");
        args.add(readOnly ? "dontAsk" : "bypassPermissions");

        if (resume != null) {
            args.add("--resume");
            args.add(resume);
        } else {
            args.add("--session-id");
            args.add(session);
        }

        if (readOnly) {
            // Removes the tools that run commands or code, for turns that only need an answer.
            args.add("--restricted");
        }

        String model = chosen(requested, fallback);
        if (model != null) {
            args.add("--model");
            args.add(model);
        }
        return args;
    }

    /** The turn's own choice wins over the run-wide default; blank means neither was set. */
    private String chosen(String requested, String fallback) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
