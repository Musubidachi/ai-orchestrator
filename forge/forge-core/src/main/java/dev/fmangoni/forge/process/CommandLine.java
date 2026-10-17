package dev.fmangoni.forge.process;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Builds the OS-level command for an executable plus arguments.
 *
 * <p>Exists solely to hold one Windows rule: npm installs global CLIs as {@code .cmd} batch shims,
 * and {@link ProcessBuilder} cannot execute those directly — they must go through {@code cmd.exe /c}.
 */
@UtilityClass
public class CommandLine {

    private final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public List<String> build(Path exe, List<String> args) {
        List<String> command = new ArrayList<>();
        if (needsShell(exe)) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(exe.toString());
        command.addAll(args);
        return command;
    }

    private static boolean needsShell(Path exe) {
        String name = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        return WINDOWS && (name.endsWith(".cmd") || name.endsWith(".bat"));
    }
}
