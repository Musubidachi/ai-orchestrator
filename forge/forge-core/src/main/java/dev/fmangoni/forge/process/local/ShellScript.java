package dev.fmangoni.forge.process.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Writes an acceptance command to a script file so it can be executed without quoting it.
 *
 * <p>Acceptance commands are arbitrary shell strings that routinely contain quotes, for example
 * {@code mvn exec:java -Dexec.args="a.pdf b.pdf out.html"}. Passing such a string through
 * {@link ProcessBuilder} to {@code cmd.exe} means it is re-quoted twice, and the argument the tool
 * finally receives is not the one the spec author wrote.
 *
 * <p>Writing it to a file removes the problem rather than escaping around it — the bytes on disk
 * are exactly the bytes from the spec. It also leaves something reproducible behind: when a project
 * is parked, the script that failed is sitting in the run's log directory ready to be run by hand.
 */
@UtilityClass
public class ShellScript {

    private final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** @return the script file, ready to execute */
    public Path write(Path dir, String name, String command) {
        Path script = dir.resolve(name + (WINDOWS ? ".bat" : ".sh"));
        try {
            Files.createDirectories(dir);
            Files.writeString(script, body(command), StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
            return script;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write gate script " + script, e);
        }
    }

    /**
     * The script, with {@code CI} set — which is what an acceptance run is: headless, unattended,
     * judged on its exit code.
     *
     * <p>Mostly for the servers test suites start. One project's Playwright config has
     * {@code reuseExistingServer: !process.env.CI}, the generated default, so without it a gate
     * would run its tests and film its features against whatever already answers on port 4173 — a
     * preview server an agent left running with last session's build, or another Vite project
     * gating at the same moment on the same default port. That is a pass for code that was never
     * tested. With it the suite starts its own server or fails saying the port is taken. Test
     * runners also stop offering watch mode, which a gate could only ever wait out.
     *
     * <p>In the script rather than the process environment, so running the script by hand from the
     * logs directory still reproduces exactly what the gate ran.
     */
    private String body(String command) {
        return WINDOWS
                ? "@echo off\r\nset \"CI=true\"\r\n" + command + "\r\nexit /b %ERRORLEVEL%\r\n"
                : "#!/bin/sh\nset -e\nexport CI=true\n" + command + "\n";
    }

    /** The command needed to run a script this class wrote. */
    public List<String> invocation(Path script) {
        return WINDOWS
                ? List.of("cmd.exe", "/c", script.toString())
                : List.of("/bin/sh", script.toString());
    }
}
