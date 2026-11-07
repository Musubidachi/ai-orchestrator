package dev.fmangoni.forge.gate.words;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.gate.GateResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The gate, holding every look of a screen to the words of its default look.
 *
 * <p>A theme or an event changes how a screen looks, never what it says. The specs used to ask the
 * project's own suite to assert that, which left the agent's test grading the agent. Instead the
 * suite saves each screen's visible text to {@code artifacts/words/<screen>.<look>.txt}, with
 * {@code default} as the look for the default theme, and the gate compares them itself.
 *
 * <p>The folder is emptied before the commands run, so only what this gate's suite saved is
 * compared; a file left from an earlier build would otherwise pass for one. A project whose suite
 * saves no words is not checked: a spec that wants them lists them in {@code evidence}, and the
 * gate already fails when evidence is missing.
 */
public final class SameWords {

    /** Where the suite saves the words, relative to the repository. */
    public static final String FOLDER = "artifacts/words";

    /** The look every other look is compared with. */
    public static final String DEFAULT = "default";

    /** How a failure of this check starts, as the command it is reported under. */
    public static final String FAILURE = "same words: ";

    private static final int SHOWN = 12;

    private SameWords() {
    }

    /** Runs the gate with the words cleared first and compared after, when it passed. */
    public static GateResult around(Path repo, Supplier<GateResult> gate) {
        clear(repo.resolve(FOLDER));
        GateResult result = gate.get();
        if (!result.passed()) {
            return result;
        }
        return check(repo).map(failure -> {
            List<CommandResult> commands = new ArrayList<>(result.commands());
            commands.add(failure);
            return new GateResult(commands, result.evidence());
        }).orElse(result);
    }

    /** What differs between the looks of each screen, as a failed command, or nothing. */
    public static Optional<CommandResult> check(Path repo) {
        List<String> problems = new ArrayList<>();
        try {
            ScreenWords.in(repo.resolve(FOLDER)).forEach((screen, looks) -> problems.addAll(problems(screen, looks)));
        } catch (UncheckedIOException e) {
            problems.add("the words could not be read: " + e.getCause().getMessage());
        }
        if (problems.isEmpty()) {
            return Optional.empty();
        }
        String detail = """
                A screen must say the same in every look. The project's suite saved what each screen \
                says to %s/<screen>.<look>.txt, and these differ from the default look's (- only in \
                the default, + only in the other look). Make the look say what the default says; if \
                the suite saved the wrong screen or state, fix the suite.

                %s
                """.formatted(FOLDER, String.join("\n", problems));
        return Optional.of(new CommandResult(FAILURE + problems.size() + " to put right", 1, detail, false,
                Duration.ZERO, ""));
    }

    private static List<String> problems(String screen, Map<String, Path> looks) {
        List<String> out = new ArrayList<>();
        if (looks.containsKey("")) {
            out.add(screen + ".txt: name it <screen>.<look>.txt, such as " + screen + "." + DEFAULT + ".txt");
        }
        Path base = looks.get(DEFAULT);
        for (Map.Entry<String, Path> look : looks.entrySet()) {
            if (look.getKey().isEmpty() || look.getKey().equals(DEFAULT)) {
                continue;
            }
            if (base == null) {
                out.add(screen + "." + look.getKey() + ".txt: there is no " + screen + "." + DEFAULT
                        + ".txt to compare it with");
                continue;
            }
            List<String> differences = ScreenWords.differences(base, look.getValue());
            if (!differences.isEmpty()) {
                out.add(screen + ", " + look.getKey() + " look:");
                differences.stream().limit(SHOWN).forEach(line -> out.add("  " + line));
                if (differences.size() > SHOWN) {
                    out.add("  … and " + (differences.size() - SHOWN) + " more");
                }
            }
        }
        return out;
    }

    private static void clear(Path folder) {
        for (Path file : ScreenWords.texts(folder)) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // Left in place, it is compared as though this gate had saved it; say so in the log.
                System.err.println("could not clear " + file + ": " + e.getMessage());
            }
        }
    }
}
