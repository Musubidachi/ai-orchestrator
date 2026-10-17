package dev.fmangoni.forge.cli.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The front door: which command runs, where it looks, and what the flags mean.
 *
 * <p>The command line stood at 0% coverage while holding every flag Forge understands. That is the
 * failure that reads as "Forge is not working" and takes an hour to trace to a mistyped argument
 * silently doing nothing — and it is all pure functions over a {@code String[]}, which is about
 * the cheapest thing there is to test.
 *
 * <p>Deliberately not testing {@code main}. Dispatch reaches the filesystem, the agents and the
 * network; what is worth pinning is the decisions taken before any of that.
 */
class ArgsTest {

    // ------------------------------------------------------- the command

    @Test
    @DisplayName("no arguments at all asks for help rather than doing something")
    void emptyArgsMeansHelp() {
        assertEquals("help", Args.commandOf(new String[0]));
    }

    @Test
    @DisplayName("the first argument is the command")
    void firstArgumentIsTheCommand() {
        assertEquals("run", Args.commandOf(new String[] {"run", "--workers", "2"}));
        assertEquals("status", Args.commandOf(new String[] {"status"}));
    }

    @ParameterizedTest(name = "{0} needs the child-process guard")
    @ValueSource(strings = {"run", "ui"})
    @DisplayName("commands that can start an agent install the guard")
    void spawningCommandsAreGuarded(String command) {
        // Missing one leaves an agent running under no parent when Forge exits — still writing
        // into a project, still spending a subscription. Seen on this machine.
        assertTrue(Args.spawnsAgents(command));
    }

    @ParameterizedTest(name = "{0} does not")
    @ValueSource(strings = {"status", "report", "rank", "prune", "help", "nonsense"})
    @DisplayName("commands that cannot start an agent do not install it")
    void inertCommandsAreNotGuarded(String command) {
        assertFalse(Args.spawnsAgents(command));
    }

    // ------------------------------------------------------- the root

    @Test
    @DisplayName("--root wins, and is made absolute")
    void rootIsTakenFromTheFlag() {
        Path root = Args.rootOf(new String[] {"run", "--root", "some-where"});

        assertTrue(root.isAbsolute(), "a relative root would mean a scheduled task and a terminal "
                + "disagreeing about whose projects they are looking at");
        assertTrue(root.endsWith("some-where"), root.toString());
    }

    @Test
    @DisplayName("without --root it is the working directory")
    void rootDefaultsToHere() {
        assertEquals(Paths.get("").toAbsolutePath(), Args.rootOf(new String[] {"status"}));
    }

    // ------------------------------------------------------- flags

    @Test
    @DisplayName("a valueless flag is present or it is not")
    void valuelessFlags() {
        assertTrue(Args.has(new String[] {"ui", "--no-resume"}, "--no-resume"));
        assertFalse(Args.has(new String[] {"ui"}, "--no-resume"));
        assertFalse(Args.has(new String[] {"ui", "--no-resum"}, "--no-resume"),
                "a near miss must not count, or a typo silently takes effect");
    }

    @Test
    @DisplayName("a flag's value is the argument after it")
    void optionReadsTheNextArgument() {
        assertEquals("7", Args.option(new String[] {"run", "--workers", "7"}, "--workers")
                .orElseThrow());
        assertTrue(Args.option(new String[] {"run"}, "--workers").isEmpty());
    }

    @Test
    @DisplayName("a flag at the very end has no value, rather than taking the next flag's")
    void trailingFlagHasNoValue() {
        // The loop stops one short on purpose. Without that, `--root` at the end would read past
        // the array, and `--workers --root x` would quietly run with "--root" workers.
        assertTrue(Args.option(new String[] {"run", "--workers"}, "--workers").isEmpty());
    }

    @Test
    @DisplayName("the first occurrence wins when a flag is given twice")
    void firstOccurrenceWins() {
        assertEquals("2", Args.option(new String[] {"run", "--workers", "2", "--workers", "9"},
                "--workers").orElseThrow());
    }

    @Test
    @DisplayName("workers defaults rather than failing when the flag is absent")
    void workersHasADefault() {
        assertEquals(4, Args.workers(new String[] {"run"}));
        assertEquals(2, Args.workers(new String[] {"run", "--workers", "2"}));
    }

    @Test
    @DisplayName("a non-numeric worker count fails loudly rather than silently defaulting")
    void workersRejectsNonsense() {
        // Loud is right here: quietly running four projects when someone asked for "two" spends
        // twice the quota they intended, and nothing would say so.
        Assertions.assertThrows(NumberFormatException.class,
                () -> Args.workers(new String[] {"run", "--workers", "two"}));
    }
}
