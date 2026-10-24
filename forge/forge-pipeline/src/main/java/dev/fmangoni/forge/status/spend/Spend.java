package dev.fmangoni.forge.status.spend;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * What every project on this machine has spent, in total and by the model that spent it.
 *
 * <p>Read fresh from each {@code state.json} on every call, with nothing kept. There is no totals
 * file, so there is nothing to drift away from the projects it is about, and a project deleted off
 * the board stops counting the moment it goes — which is the honest answer to "what has this
 * machine spent", since the thing it was spent on no longer exists.
 *
 * <p>The usage ledger does not answer this question and cannot be made to. It is keyed by vendor,
 * it resets each time a window runs out, and it remembers only the last five windows — so on this
 * machine its lifetime figure came to less than one project's own total. The ledger measures a
 * window, which is what it is for. This measures the work.
 *
 * <p><b>Nothing reads this but the dashboard.</b> No brief mentions it, no agent is shown it, and it
 * chooses nothing: a number an agent could see is a number an agent would work towards.
 */
@UtilityClass
public class Spend {

    /** Every project under {@code runsDir} that has spent anything. */
    public Spending of(Path runsDir) {
        Totals totals = new Totals();
        for (String id : runIds(runsDir)) {
            RunStore.survey(RunPaths.under(runsDir, id).stateFile()).ifPresent(totals::add);
        }
        return totals.spending();
    }

    /**
     * The run directories, by name.
     *
     * <p>Listed here rather than shared with the league table, which lists them the same way: the
     * package that owns {@code RunPaths} is at its class limit, and a directory listing is a poorer
     * reason to force that boundary open than the next thing that needs it will be.
     */
    private List<String> runIds(Path runsDir) {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(runsDir)) {
            return entries.filter(Files::isDirectory)
                    .map(entry -> entry.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("could not list " + runsDir + ": " + e.getMessage());
            return List.of();
        }
    }
}
