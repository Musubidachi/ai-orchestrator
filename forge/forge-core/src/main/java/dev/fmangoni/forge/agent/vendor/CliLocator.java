package dev.fmangoni.forge.agent.vendor;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Finds the agent CLIs on disk.
 *
 * <p>On Windows npm installs global binaries as {@code .cmd} shims in {@code %APPDATA%\npm}, which
 * is frequently absent from the environment a scheduled task inherits. Resolving to an absolute
 * path here means Forge does not depend on how it was launched.
 */
@UtilityClass
public class CliLocator {

    private final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /**
     * @throws IllegalStateException if the CLI is not installed, naming the install command —
     *         a missing CLI is a setup problem, and failing loudly at startup beats every project
     *         in the queue failing mysteriously at build time.
     */
    public Path require(AgentKind kind) {
        return find(kind).orElseThrow(() -> new IllegalStateException(
                kind.cliName() + " CLI not found. Install it with: " + installHint(kind)));
    }

    /** How to get this CLI, which is not the same kind of answer for all of them. */
    private String installHint(AgentKind kind) {
        return switch (kind) {
            case CLAUDE -> "npm i -g @anthropic-ai/claude-code";
            case CODEX -> "npm i -g @openai/codex";
            // Not an npm package: Google ships an installer script that puts agy on the PATH.
            case ANTIGRAVITY -> WINDOWS
                    ? "irm https://antigravity.google/cli/install.ps1 | iex"
                    : "curl -fsSL https://antigravity.google/cli/install.sh | bash";
        };
    }

    /** Whether this vendor's CLI is on the machine at all. */
    public boolean installed(AgentKind kind) {
        return find(kind).isPresent();
    }

    public Optional<Path> find(AgentKind kind) {
        for (Path candidate : candidates(kind.cliName())) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    private List<Path> candidates(String name) {
        List<Path> out = new ArrayList<>();
        List<String> exts = WINDOWS ? List.of(".cmd", ".exe", "") : List.of("");

        String appData = System.getenv("APPDATA");
        if (appData != null) {
            for (String ext : exts) {
                out.add(Paths.get(appData, "npm", name + ext));
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            for (String ext : exts) {
                out.add(Paths.get(home, ".local", "bin", name + ext));
            }
        }
        // Antigravity's installer does not use npm; it drops agy.exe here and edits the user's
        // PATH in the registry. A shell opened before the install — or a scheduled task, which
        // inherits a minimal environment — never sees that, so the location is named outright.
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            for (String ext : exts) {
                out.add(Paths.get(localAppData, name, "bin", name + ext));
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String ext : exts) {
                    out.add(Paths.get(dir, name + ext));
                }
            }
        }
        return out;
    }
}
