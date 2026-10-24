package dev.fmangoni.forge.deploy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a program can be run by name, looked up the way the shell would.
 *
 * <p>Checked before Deploy is offered, so the button says "wrangler is not installed" instead of
 * failing a minute into a deploy with a shell error. Only looks: nothing is ever installed.
 */
public final class Executables {

    private Executables() {
    }

    static boolean onPath(String name, Map<String, String> environment) {
        return find(name, environment).isPresent();
    }

    /** Where {@code name} would run from, by the PATH in {@code environment}, if anywhere. */
    public static Optional<Path> find(String name, Map<String, String> environment) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            return Optional.empty();
        }
        String path = variable(environment, "PATH").orElse("");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> extensions = windows
                ? List.of(variable(environment, "PATHEXT").orElse(".COM;.EXE;.BAT;.CMD").toLowerCase(Locale.ROOT).split(";"))
                : List.of("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Path.of(dir.strip(), name + (name.toLowerCase(Locale.ROOT).endsWith(extension) ? "" : extension));
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * An environment variable by name, in any case, as Windows itself reads it.
     *
     * <p>Java's map of the environment is case-sensitive, and the names in it are spelled however
     * whatever started the process spelled them: a JVM started from Maven's test runner sees
     * {@code PROGRAMFILES} and {@code PROGRAMW6432}, so looking for {@code ProgramFiles} found
     * nothing and Forge said gh was not installed when it was.
     */
    public static Optional<String> variable(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
