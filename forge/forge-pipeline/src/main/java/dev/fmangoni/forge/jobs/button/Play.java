package dev.fmangoni.forge.jobs.button;

import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Opens a project's {@code run.bat}, so trying it is one click on the card.
 *
 * <p>Exactly what double-clicking the file does, and nothing more: a console window of its own, in
 * the project's folder, running the file. Forge does not watch it, time it, or stop it — closing
 * the window is how it is stopped, as the run file itself says. It is deliberately not a job: it
 * takes no worker and no repository lock, and it is not recorded as something Forge started, so
 * nothing here will ever tidy it away as a leftover.
 *
 * <p>Refused while the project is being worked on. A build running underneath a gate holds the
 * files the gate is about to delete, and an agent's dev server and yours would be arguing over the
 * same port.
 */
public final class Play {

    private final Projects projects;
    private final JobPool pool;
    private final Consumer<Path> opener;

    public Play(Projects projects, JobPool pool) {
        this(projects, pool, Play::openInItsOwnWindow);
    }

    /** With the window-opening replaced, so a test can press Play without one appearing. */
    Play(Projects projects, JobPool pool, Consumer<Path> opener) {
        this.projects = projects;
        this.pool = pool;
        this.opener = opener;
    }

    public String start(String id) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        String title = found.get().spec().title();
        Path repo = found.get().paths().repo();
        if (pool.isRunning(id)) {
            return title + " is being worked on. Playing it now would get in the agent's way; "
                    + "try again when it stops.";
        }
        if (!RunFile.present(repo)) {
            return title + " has no " + RunFile.NAME + " yet. It gets one the next time an agent "
                    + "works on it.";
        }
        if (!windows()) {
            return RunFile.NAME + " needs Windows. Run the project from " + repo + " by hand.";
        }
        try {
            opener.accept(repo);
        } catch (UncheckedIOException e) {
            return "could not open " + RunFile.NAME + ": " + e.getCause().getMessage();
        }
        return "Opened " + title + " in its own window. Close that window to stop it.";
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * {@code start} gives the run file a console of its own, which is what makes it outlive this
     * call and belong to the person rather than to Forge. The empty first argument is the window
     * title {@code start} insists on taking before a quoted program.
     *
     * <p>The file is named as {@code .\run.bat}, and one variable is taken out of the environment,
     * for the same reason: with {@code NoDefaultCurrentDirectoryInExePath} set, {@code cmd} will
     * not run a file from the current folder by its bare name, and the first version of this
     * opened a window that said "'run.bat' is not recognized" and closed. An editor started from a
     * hardened shell inherits that variable; a double-click never has it. Taking it out is what
     * makes Play show the same thing a double-click would, including for a run file that calls a
     * second script beside it.
     */
    private static void openInItsOwnWindow(Path repo) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "cmd.exe", "/c", "start", "", "cmd.exe", "/c", ".\\" + RunFile.NAME)
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().remove("NoDefaultCurrentDirectoryInExePath");
            builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
