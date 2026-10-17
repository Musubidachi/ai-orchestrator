package dev.fmangoni.forge.agent.vendor.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The Codex command line, kept separate from the code that runs it.
 *
 * <p>Three of these were learned by being wrong about them, which is the argument for having a
 * file that states them plainly:
 *
 * <ul>
 *   <li>{@code --skip-git-repo-check} is required, not optional. Without it Codex refuses to start
 *       with "Not inside a trusted directory", and that is a <i>trust</i> check rather than merely a
 *       git one — running {@code git init} in the workspace does not satisfy it.
 *   <li>{@code resume} is a subcommand and accepts a narrower set of options than a fresh
 *       {@code exec}: {@code -C} and {@code -s} are rejected outright. The working directory is set
 *       on the process instead.
 *   <li><b>A resumed session does not keep its sandbox.</b> On 0.153.4 it carried over from the
 *       session being resumed, and this class relied on that. On 0.159.3 a resumed turn given no
 *       sandbox runs read-only: asked to write a file in its own workspace it was refused with
 *       "Access to the path … is denied". Every turn after a project's first is a resume, so every
 *       one of them would have built nothing. {@code -c sandbox_mode=…} is accepted where
 *       {@code -s} is not, and is passed on every resume.
 * </ul>
 *
 * <p>{@code workspace-write} is the sandbox boundary — the agent may write inside the project
 * directory (and the temp directory) and not outside it. It is a real enforcement mechanism rather
 * than a convention, which is why Codex needs no equivalent of Claude's permission bypass. Checked
 * on 0.159.3 for both a fresh and a resumed turn: a file inside was written, one in the parent
 * directory was denied.
 *
 * <p>Verified against codex-cli 0.159.3 on 2026-10-01.
 */
@UtilityClass
public class CodexArgs {

    /** Codex reads the prompt from stdin when the positional argument is a bare dash. */
    private final String STDIN_PROMPT = "-";

    /**
     * What a value from {@code vendors.json} may look like to be put on a command line.
     *
     * <p>These reach {@code cmd.exe} through the npm shim, where a quote or an ampersand in a
     * hand-edited settings file would be parsed as shell. Feature and sandbox names are plain
     * identifiers; anything else is not one of them and is left off.
     */
    private final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    /**
     * Builds either {@code exec [OPTIONS] -} or {@code exec resume [OPTIONS] <id> -}.
     *
     * @param cwd         the project directory, passed only on a fresh session
     * @param lastMessage where Codex should write its closing message
     * @param readOnly    run in the read-only sandbox
     * @param resume      an existing session to continue, or {@code null} for a new one
     * @param requested   the model this turn asked for
     * @param fallback    the run-wide model, used when the turn asked for nothing
     */
    public List<String> of(Path cwd, Path lastMessage, boolean readOnly, String resume,
                           String requested, String fallback) {
        return of(cwd, lastMessage, readOnly, resume, requested, fallback, CodexOptions.none());
    }

    /**
     * @param options the sandbox to use and the features to leave out, from {@code vendors.json}
     */
    public List<String> of(Path cwd, Path lastMessage, boolean readOnly, String resume,
                           String requested, String fallback, CodexOptions options) {
        String sandbox = readOnly ? "read-only" : "workspace-write";
        List<String> args = new ArrayList<>();
        args.add("exec");
        if (resume != null) {
            args.add("resume");
        }

        args.add("--json");
        args.add("--skip-git-repo-check");
        args.add("-o");
        args.add(lastMessage.toString());

        String model = chosen(requested, fallback);
        if (model != null) {
            args.add("-m");
            args.add(model);
        }
        args.addAll(overrides(options));

        if (resume == null) {
            args.add("-C");
            args.add(cwd.toString());
            args.add("-s");
            args.add(sandbox);
        } else {
            // No quotes: an unquoted value that is not TOML is taken as a literal string, and a
            // quoted one would have to survive cmd.exe.
            args.add("-c");
            args.add("sandbox_mode=" + sandbox);
            args.add(resume);
        }

        args.add(STDIN_PROMPT);
        return args;
    }

    /** The per-run settings, on a fresh turn and a resumed one alike: neither is remembered. */
    private List<String> overrides(CodexOptions options) {
        List<String> args = new ArrayList<>();
        if (plain(options.windowsSandbox())) {
            args.add("-c");
            args.add("windows.sandbox=" + options.windowsSandbox());
        }
        for (String feature : options.disabledFeatures()) {
            if (plain(feature)) {
                args.add("--disable");
                args.add(feature);
            }
        }
        for (String server : options.disabledMcpServers()) {
            // Checked with `codex mcp list`, which calls no model: the server's status goes from
            // enabled to disabled. Overriding the whole table with an empty one does not remove it.
            if (plain(server) && !server.contains(".")) {
                args.add("-c");
                args.add("mcp_servers." + server + ".enabled=false");
            }
        }
        return args;
    }

    private boolean plain(String value) {
        return value != null && PLAIN_NAME.matcher(value).matches();
    }

    /** The turn's own choice wins over the run-wide default; blank means neither was set. */
    private String chosen(String requested, String fallback) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
