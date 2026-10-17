package dev.fmangoni.forge.security.layer;

import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.process.local.ShellScript;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Runs a scanner's command line in the project, the way the gate runs acceptance.
 *
 * <p>An interface so tests can stand in for {@code npm audit} and {@code mvn} with canned output,
 * without a network or a dependency tree.
 */
@FunctionalInterface
public interface Shell {

    /**
     * @param name    names the script and its log, e.g. {@code npm-audit}
     * @param command a shell command line, run from the repository root
     * @return the process result; scanners write anything large to a file themselves
     */
    ProcResult run(String name, String command);

    /** The real thing: a script in {@code scriptDir}, output logged beside it, killed at the cap. */
    static Shell in(Path repo, Path scriptDir, Duration timeout) {
        return (name, command) -> {
            Path script = ShellScript.write(scriptDir, name, command);
            List<String> invocation = ShellScript.invocation(script);
            return CliRunner.run(Path.of(invocation.get(0)), invocation.subList(1, invocation.size()),
                    repo, null, timeout, scriptDir.resolve(name + ".log"));
        };
    }

    /** Whether the shell reported that the program itself does not exist. */
    static boolean missing(ProcResult result) {
        String text = result.combined().toLowerCase(Locale.ROOT);
        return result.exitCode() == 9009 || result.exitCode() == 127
                || text.contains("is not recognized as an internal or external command")
                || text.contains("command not found");
    }
}
