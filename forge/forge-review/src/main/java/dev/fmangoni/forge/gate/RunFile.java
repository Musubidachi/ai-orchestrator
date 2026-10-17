package dev.fmangoni.forge.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The file a person double-clicks to try a project, which every project owes.
 *
 * <p>Six projects were built here before anyone asked how to start one. Every one of them could be
 * run — the tests that film their features start them every gate — but only by the tests. Three
 * had a README with commands in it, three had nothing at all, and none had anything to click. The
 * person these are built for was the one party with no way in.
 *
 * <p>Checked for existing and for doing something, and no further. Running it is the one thing the
 * gate cannot do: it opens a window or a browser and does not exit. Whether it works is for the
 * person, which is the point of it.
 */
@UtilityClass
public class RunFile {

    /** One exact name in one exact place, so nobody has to guess where it is or how it is spelt. */
    public final String NAME = "run.bat";

    /** The commands a batch file can consist of without starting anything. */
    private final List<String> DOES_NOTHING = List.of(
            "echo", "rem", "pause", "set", "setlocal", "endlocal", "exit", "cd", "chdir", "pushd",
            "popd", "title", "cls", "goto", "chcp");

    public boolean present(Path repo) {
        return Files.isRegularFile(repo.resolve(NAME));
    }

    /** The failure to report when the project has no usable run file, or empty when it has one. */
    public Optional<CommandResult> check(Path repo) {
        return problem(repo).map(RunFile::failure);
    }

    /**
     * What is wrong with the project's run file, in one sentence, or empty when nothing is.
     *
     * <p>The gate and the note in an agent's brief both ask this, so the two cannot disagree: a
     * stub that silenced the note while the gate still failed would cost the project a repair
     * round to learn what the note could have said.
     */
    public Optional<String> problem(Path repo) {
        if (!present(repo)) {
            return Optional.of("There is no `" + NAME + "` in the repository root.");
        }
        if (linesOf(repo.resolve(NAME)).stream().noneMatch(RunFile::doesSomething)) {
            return Optional.of("`" + NAME + "` exists but starts nothing: every line in it is a comment, "
                    + "an echo or a pause.");
        }
        return Optional.empty();
    }

    /** Whether a line runs a program, judged by its first word. Labels and {@code ::} comments do not. */
    private boolean doesSomething(String line) {
        String command = line.strip().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        if (command.isEmpty() || command.startsWith(":")) {
            return false;
        }
        return !DOES_NOTHING.contains(command.split("[\\s(/.=]", 2)[0]);
    }

    /**
     * The file's lines, read so that no encoding can make them unreadable.
     *
     * <p>As Latin-1, one byte one character, because only the ASCII command words matter here and
     * a batch file is in whatever code page its writer used. Read as UTF-8, a run file saved by
     * PowerShell's {@code Set-Content} — the ANSI code page — with a single em dash in a comment
     * threw, and the gate reported a working file as one in which every line was a comment.
     * A UTF-8 byte-order mark reads as three characters in front of the first word, and is removed.
     */
    private List<String> linesOf(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            return text.replaceFirst("^\u00EF\u00BB\u00BF", "").lines().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private CommandResult failure(String what) {
        String detail = """
                The acceptance commands passed, but the project cannot be tried by the person it \
                was built for. %s

                Every project needs a `%s` in the repository root that they can double-click: it \
                installs and builds whatever is missing, then starts the real program — the server \
                and a browser for a web app, the window for a desktop app, a worked example on \
                sample input for a command-line tool or a library. It must not ask anything, and \
                must not close on an error before it can be read.

                What it has to do for this stack is in AGENTS.md under "A way to run it". Do not \
                add it to the acceptance commands: it does not exit.""".formatted(what, NAME);
        return new CommandResult("run file: " + NAME, 1, detail, false, Duration.ZERO, "");
    }
}
