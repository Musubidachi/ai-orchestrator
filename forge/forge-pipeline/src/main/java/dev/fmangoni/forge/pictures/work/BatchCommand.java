package dev.fmangoni.forge.pictures.work;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.pictures.model.CommandRun;
import dev.fmangoni.forge.pictures.model.Verdict;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.process.local.ShellScript;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Runs one of a card's commands from the batch folder, as the card says to run it.
 *
 * <p>Written to a script first, as the gate does, so the command runs exactly as the card spells it
 * and can be run again by hand from {@code logs/}. Its whole output goes to a log there; the record
 * keeps what it printed, which is what the review shows.
 */
@UtilityClass
public class BatchCommand {

    /** Lines kept in the record. A check prints a dozen; a crash's traceback is cut to its end. */
    private final int MOST_LINES = 60;

    /**
     * @param name  names the script and the log, such as {@code check-drafts-lantern-market}
     * @param stamp the picture it runs on
     */
    public CommandRun run(Path root, String name, String command, String stamp, Duration timeout) {
        Path logs = root.resolve("logs");
        Path log = logs.resolve(name + ".log");
        try {
            // The runner adds to a log; this one is about this run alone, as its link says.
            Files.deleteIfExists(log);
        } catch (IOException e) {
            // Then this run is added after the last one, which still says everything.
        }
        List<String> invocation = ShellScript.invocation(ShellScript.write(logs, name, command));
        ProcResult result = CliRunner.run(Paths.get(invocation.get(0)), invocation.subList(1, invocation.size()),
                root, null, timeout, log);
        return new CommandRun(stamp, Instant.now(), result.timedOut() ? -1 : result.exitCode(), verdict(result),
                lines(result), "logs/" + name + ".log");
    }

    /** What an exit code means, as a gate command's does: 42 passes with something to look at. */
    public Verdict verdict(ProcResult result) {
        if (result.timedOut()) {
            return Verdict.ERROR;
        }
        return switch (result.exitCode()) {
            case 0 -> Verdict.OK;
            case CommandResult.LOOK -> Verdict.LOOK;
            case 1 -> Verdict.PROBLEM;
            default -> Verdict.ERROR;
        };
    }

    /** A name for a job's scripts and logs, from its picture's path: {@code drafts/lantern/market.png} gives {@code drafts-lantern-market}. */
    public String slug(String draft) {
        String name = draft.replaceAll("\\.[A-Za-z0-9]+$", "").toLowerCase(Locale.ROOT);
        return name.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private List<String> lines(ProcResult result) {
        List<String> lines = new ArrayList<>();
        result.stdout().lines().filter(line -> !line.isBlank()).forEach(lines::add);
        result.stderr().lines().filter(line -> !line.isBlank()).forEach(lines::add);
        return lines.size() > MOST_LINES ? lines.subList(lines.size() - MOST_LINES, lines.size()) : lines;
    }
}
