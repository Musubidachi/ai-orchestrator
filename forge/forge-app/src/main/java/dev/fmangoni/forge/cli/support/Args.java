package dev.fmangoni.forge.cli.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The command line, read by hand.
 *
 * <p>Hand-rolled rather than delegated to a CLI library: there are six subcommands and a handful of
 * options, and a dependency would be more code to audit than it removes. Tested on its own because
 * a mistyped flag silently ignored is the failure that looks like "Forge is not working" and takes
 * an hour to trace to an argument.
 */
@UtilityClass
public class Args {

    /** Larger than the per-vendor limit so projects compiling in their gate do not idle a CLI. */
    final int DEFAULT_WORKERS = 4;

    /** The subcommand, defaulting to help. */
    public String commandOf(String[] args) {
        return args.length == 0 ? "help" : args[0];
    }

    /**
     * Where the wishlist and runs live: {@code --root}, or the working directory.
     *
     * <p>Absolute either way. A relative root resolved lazily would mean a scheduled task and a
     * terminal disagreeing about which machine's projects they were looking at.
     */
    public Path rootOf(String[] args) {
        return option(args, "--root").map(Paths::get)
                .orElseGet(() -> Paths.get("").toAbsolutePath())
                .toAbsolutePath();
    }

    /**
     * Whether this command can start an agent, and therefore needs the child-process guard.
     *
     * <p>Only these two. Installing a shutdown hook for {@code status} or {@code report} would be
     * noise — but missing one for a command that does spawn leaves an agent running under no
     * parent, still writing and still spending a subscription.
     */
    public boolean spawnsAgents(String command) {
        return command.equals("run") || command.equals("ui");
    }

    public int workers(String[] args) {
        return option(args, "--workers").map(Integer::parseInt).orElse(DEFAULT_WORKERS);
    }

    /** A flag with no value of its own, such as {@code --no-resume}. */
    public boolean has(String[] args, String name) {
        return Arrays.asList(args).contains(name);
    }

    public Optional<String> option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Optional.of(args[i + 1]);
            }
        }
        return Optional.empty();
    }
}
