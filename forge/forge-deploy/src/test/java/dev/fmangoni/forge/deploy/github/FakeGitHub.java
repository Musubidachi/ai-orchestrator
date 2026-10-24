package dev.fmangoni.forge.deploy.github;

import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An account whose repositories are bare git repositories on disk.
 *
 * <p>Only the account is pretend. What is pushed into one of these is pushed by the real git, so a
 * test that reads a branch back here has seen a real push land.
 */
final class FakeGitHub implements GitHubAccount {

    final List<String> created = new ArrayList<>();
    final Set<String> existing = new HashSet<>();

    private final Path home;
    private final String login;

    /** @param login the account, or null for a CLI that is not logged in */
    FakeGitHub(Path home, String login) {
        this.home = home;
        this.login = login;
    }

    @Override
    public Optional<String> login() {
        return Optional.ofNullable(login);
    }

    @Override
    public Optional<String> urlOf(String repository) {
        return existing.contains(repository)
                ? Optional.of(url(repository.substring(repository.indexOf('/') + 1)))
                : Optional.empty();
    }

    @Override
    public String createPrivate(String name, String description) {
        created.add(name);
        try {
            Files.createDirectories(home);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(home, "init", "--bare", "-q", bare(name).toString());
        existing.add(login + "/" + name);
        return url(name);
    }

    /** A repository on disk asks for no credentials. */
    @Override
    public String credentialHelper() {
        return "";
    }

    /** Each branch the repository has, and the commit it is at. */
    Map<String, String> branches(String name) {
        Map<String, String> branches = new LinkedHashMap<>();
        if (!Files.isDirectory(bare(name))) {
            return branches;
        }
        git(home, "--git-dir=" + bare(name), "for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads")
                .stdout().lines().map(String::strip).filter(line -> !line.isEmpty())
                .forEach(line -> branches.put(line.substring(0, line.indexOf(' ')), line.substring(line.indexOf(' ') + 1)));
        return branches;
    }

    private String url(String name) {
        return bare(name).toString().replace('\\', '/');
    }

    private Path bare(String name) {
        return home.resolve(name + ".git");
    }

    private ProcResult git(Path cwd, String... args) {
        ProcResult result = CliRunner.run(Path.of("git"), List.of(args), cwd, null, Duration.ofMinutes(1),
                home.resolveSibling("fake-github.log"));
        if (!result.ok()) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.combined());
        }
        return result;
    }
}
