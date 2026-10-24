package dev.fmangoni.forge.deploy.github;

import dev.fmangoni.forge.deploy.Executables;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub through the person's own {@code gh}, logged in by them.
 *
 * <p>Forge never logs in, and never reads or stores a token. When {@code gh} is not logged in, it
 * says so and the person runs {@code gh auth login} themselves, exactly as with the vendor CLIs.
 */
public final class GitHubCli implements GitHubAccount {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern URL = Pattern.compile("https://github\\.com/\\S+");

    private final Path gh;
    private final Path cwd;
    private final Path log;

    public GitHubCli(Path gh, Path cwd, Path log) {
        this.gh = gh;
        this.cwd = cwd;
        this.log = log;
    }

    /**
     * The {@code gh} on PATH, or where its installers put it.
     *
     * <p>The installer's folders too, because PATH is read when a process starts: an editor that was
     * already running when {@code gh} was installed has a PATH without it, and would say it is not
     * installed. Only looks; nothing is ever installed.
     */
    public static Optional<Path> find(Map<String, String> environment) {
        Optional<Path> onPath = Executables.find("gh", environment);
        if (onPath.isPresent()) {
            return onPath;
        }
        for (String base : List.of("ProgramFiles", "ProgramW6432", "LOCALAPPDATA")) {
            String dir = Executables.variable(environment, base).orElse(null);
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (Path candidate : List.of(Path.of(dir, "GitHub CLI", "gh.exe"),
                    Path.of(dir, "Programs", "GitHub CLI", "gh.exe"))) {
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> login() {
        ProcResult result = gh("api", "user", "--jq", ".login");
        String login = result.stdout().strip();
        return result.ok() && !login.isEmpty() ? Optional.of(login) : Optional.empty();
    }

    @Override
    public Optional<String> urlOf(String repository) {
        ProcResult result = gh("repo", "view", repository, "--json", "url", "--jq", ".url");
        if (result.ok()) {
            return Optional.of(result.stdout().strip());
        }
        if (result.combined().contains("Could not resolve to a Repository")) {
            return Optional.empty();
        }
        // Not knowing is not the same as it not being there, and creating one over it would be wrong.
        throw new IllegalStateException("gh could not say whether " + repository + " exists: " + firstLine(result));
    }

    @Override
    public String createPrivate(String name, String description) {
        ProcResult result = gh("repo", "create", name, "--private", "--description", description);
        Matcher url = URL.matcher(result.stdout());
        if (!result.ok() || !url.find()) {
            throw new IllegalStateException("gh could not create " + name + ": " + firstLine(result));
        }
        return url.group();
    }

    /** The line {@code gh auth setup-git} would write, handed to one git command instead of the person's config. */
    @Override
    public String credentialHelper() {
        return "!'" + gh.toString().replace('\\', '/') + "' auth git-credential";
    }

    private ProcResult gh(String... args) {
        return CliRunner.run(gh, new ArrayList<>(List.of(args)), cwd, null, TIMEOUT, log);
    }

    private static String firstLine(ProcResult result) {
        return result.combined().strip().lines().findFirst().orElse("exit " + result.exitCode());
    }
}
