package dev.fmangoni.forge.state;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the acceptance commands passed but asked a person to look at.
 *
 * <p>A command can only pass or fail, and some checks know something is worth a person's eye
 * without knowing it is wrong: the event mockup check scores small interface changes as LOOK and
 * large ones as a problem, because finished screens and planted mistakes overlap in between. Such a
 * command exits {@link CommandResult#LOOK}. The gate counts that as a pass, and each line of its
 * output starting {@code LOOK} becomes an item in the person's review, which has to be answered on
 * its own before the project is accepted.
 *
 * <p>Recorded on the state by every gate, like {@link Timings}, so a later gate that no longer
 * asks takes its items away.
 */
public final class GateLooks {

    /** How the review labels these items. */
    public static final String SOURCE = "The gate asks you to look";

    private static final String PREFIX = "look";

    private GateLooks() {
    }

    /** Keeps what this gate's commands asked a person to look at, replacing the last gate's. */
    public static void record(RunState state, List<CommandResult> commands) {
        state.setGateLooks(from(commands));
    }

    /** One line per thing to look at, each saying which command asked. */
    public static List<String> from(List<CommandResult> commands) {
        List<String> out = new ArrayList<>();
        for (CommandResult command : commands == null ? List.<CommandResult>of() : commands) {
            if (command.timedOut() || command.exitCode() != CommandResult.LOOK) {
                continue;
            }
            List<String> said = lookLines(command.output());
            if (said.isEmpty()) {
                out.add("`" + command.command() + "` passed and asks you to look at its output in the gate log");
            }
            for (String line : said) {
                out.add("`" + command.command() + "`: " + line);
            }
        }
        return out;
    }

    /** The review's items with the gate's looks added after them. */
    public static List<ReviewItem> with(List<ReviewItem> items, RunState state) {
        List<ReviewItem> out = new ArrayList<>(items);
        List<String> looks = state == null || state.getGateLooks() == null ? List.of() : state.getGateLooks();
        for (String look : looks) {
            out.add(ReviewItem.of(PREFIX, SOURCE, look));
        }
        return out;
    }

    /** Whether the review should ask about this item on its own rather than in a batch. */
    public static boolean asks(ReviewItem item) {
        return SOURCE.equals(item.source());
    }

    /**
     * Lines starting LOOK, whatever their case. Only these, and never the whole output, because an
     * item is known by its words: output that carries a time or a path would make it a new item at
     * every gate, and what the person said about it would not follow it.
     */
    private static List<String> lookLines(String output) {
        List<String> out = new ArrayList<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith(PREFIX.toUpperCase(Locale.ROOT)) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
