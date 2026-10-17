package dev.fmangoni.forge.deploy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a program can be run by name, looked up the way the shell would.
 *
 * <p>Checked before Deploy is offered, so the button says "wrangler is not installed" instead of
 * failing a minute into a deploy with a shell error. Only looks: nothing is ever installed.
 */
final class Executables {

    private Executables() {
    }

    static boolean onPath(String name, Map<String, String> environment) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            return false;
        }
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> extensions = windows
                ? List.of(environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").toLowerCase(Locale.ROOT).split(";"))
                : List.of("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Path.of(dir.strip(), name + (name.toLowerCase(Locale.ROOT).endsWith(extension) ? "" : extension));
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return true;
                }
            }
        }
        return false;
    }
}
