package dev.fmangoni.forge.deploy.shelf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.fmangoni.forge.deploy.github.GitHubCli;
import dev.fmangoni.forge.deploy.github.Publication;
import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Publishing and shelving against the real GitHub, with the {@code gh} that is logged in here.
 *
 * <p>The proof the rest stands on: every other GitHub test pushes into repositories on disk, and
 * only this one reaches the account. It never runs by itself, and verify.bat never runs it:
 *
 * <pre>mvn test -pl forge-deploy -am -Dtest=LiveGitHubTest "-Dforge.live.github=true" "-Djacoco.skip=true"</pre>
 *
 * <p>It uses one private repository, {@code forge-selftest}, made by its first run and reused by
 * every run after, which adds one enhancement branch to it each time. It never deletes a repository
 * and never force-pushes. A run that finds the repository without this test's marker file on its
 * main, or a shelf left on it by a run that did not finish, stops before changing anything.
 *
 * <p>Each run walks the whole of it, in Forge's own classes: publishing the initial version (making
 * the repository the first time), beginning an enhancement on its own branch, approving it, shelving
 * the family with uncommitted work, a picture and a log big enough to go in pieces, Resume refusing
 * files in its way, and resuming exactly as it was, the shelf taken off GitHub.
 */
@EnabledIfSystemProperty(named = "forge.live.github", matches = "true")
class LiveGitHubTest {

    private static final String FAMILY = "900";
    private static final String TITLE = "Forge Selftest";
    private static final String MARKER = "FORGE-SELFTEST.md";
    private static final long PART = 1000;

    @TempDir
    Path root;

    private Path runs;
    private Path repo;
    private GitHubCli github;

    @Test
    @DisplayName("published, branched, approved, shelved, deleted here, refused over leftovers, and resumed exactly")
    void theWholeRoundTrip() throws Exception {
        runs = root.resolve("runs");
        repo = RunPaths.under(runs, FAMILY).repo();
        Path gh = GitHubCli.find(System.getenv()).orElseThrow(() -> new AssertionError("gh is not installed"));
        github = new GitHubCli(gh, root, root.resolve("gh.log"));
        String login = github.login().orElseThrow(() -> new AssertionError("gh is not logged in"));
        String repository = login + "/forge-selftest";

        // 1. The initial version on main: the repository made by Forge the first time, reused after.
        Optional<String> existing = github.urlOf(repository);
        String initial = existing.isEmpty() ? firstRun() : laterRun(existing.get(), repository);
        version(FAMILY, TITLE, Phase.ACCEPTED, initial, null);
        Publication published = new Publisher(runs, FAMILY, github).publish();
        assertEquals(Publication.PUBLISHED, published.outcome(), published.detail());
        String remote = published.remote();
        assertEquals(initial, heads(remote).get("main"), "main is the initial version");
        assertFalse(heads(remote).containsKey("shelf/" + FAMILY),
                "a shelf left by a run that did not finish: look at it on GitHub before running again");

        // 2. An enhancement begins: its branch is made at its first commit.
        int n = 1 + (int) heads(remote).keySet().stream().filter(name -> name.startsWith(FAMILY + ".")).count();
        String id = FAMILY + "." + n;
        String branch = id + "-selftest-change";
        Files.writeString(repo.resolve("IMPROVEMENT.md"), "Selftest change " + n + "\n");
        String first = commit(id, "Requested improvement: Selftest Change");
        version(id, "Selftest Change", Phase.GATING, null, first);
        Publication begun = new Publisher(runs, id, github).begin(id, "Selftest Change", first);
        assertEquals(Publication.PUBLISHED, begun.outcome(), begun.detail());
        assertEquals(first, heads(remote).get(branch), "made as it began, at its first commit");

        // 3. It is approved: its branch moves to its tip, and main stays where it was.
        Files.writeString(repo.resolve("change.txt"), "made at " + Instant.now() + "\n");
        String tip = commit(id, "Build (CLAUDE)");
        version(id, "Selftest Change", Phase.ACCEPTED, tip, first);
        Publication approved = new Publisher(runs, id, github).publish();
        assertEquals(Publication.PUBLISHED, approved.outcome(), approved.detail());
        assertEquals(tip, heads(remote).get(branch));
        assertEquals(initial, heads(remote).get("main"), "main never moves for an enhancement");

        // 4. Shelved, with what history does not hold, and deleted here once GitHub has it.
        Files.writeString(repo.resolve("change.txt"), "an edit nobody committed\n");
        Files.writeString(repo.resolve("notes.txt"), "a new file\n");
        Files.createDirectories(repo.resolve(".forge-images"));
        Files.write(repo.resolve(".forge-images/mockup.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        byte[] log = new byte[2500];
        new Random(9).nextBytes(log);
        Path bigLog = RunPaths.under(runs, FAMILY).logs().resolve("claude.log");
        Files.write(bigLog, log);
        Git git = new Git(repo, RunPaths.under(runs, FAMILY).gitLog());
        String localBranch = git.currentBranch().orElseThrow();

        String shelved = new Shelver(runs, FAMILY, github, PART).shelve();

        assertEquals("shelved on GitHub as shelf/" + FAMILY + " in " + repository + ", and deleted here", shelved);
        assertNotNull(heads(remote).get("shelf/" + FAMILY), "GitHub has the shelf");
        assertFalse(Files.exists(repo), "the repository is gone from here");
        assertTrue(Set.of("state.json", "shelved.json", "shelf.log").containsAll(namesIn(RunPaths.under(runs, FAMILY).root())),
                namesIn(RunPaths.under(runs, FAMILY).root()).toString());
        assertEquals(Set.of("state.json", "shelved.json"), namesIn(RunPaths.under(runs, id).root()));

        // 5. Resume refuses anything already where the repository goes, and touches nothing.
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("stray.txt"), "in the way");
        String refused = new Shelver(runs, FAMILY, github, PART).resume();
        assertTrue(refused.startsWith("not resumed, and nothing was cloned: files are already at "), refused);
        assertEquals("in the way", Files.readString(repo.resolve("stray.txt")));
        assertNotNull(heads(remote).get("shelf/" + FAMILY), "the shelf is still on GitHub");
        Files.delete(repo.resolve("stray.txt"));
        Files.delete(repo);

        // 6. Resumed exactly as it was, and the shelf taken off GitHub.
        String resumed = new Shelver(runs, FAMILY, github, PART).resume();

        assertEquals("resumed here, and shelf/" + FAMILY + " was taken off GitHub", resumed);
        assertEquals(tip, git.head(), "back at its own last commit");
        assertEquals(Optional.of(localBranch), git.currentBranch());
        assertEquals("an edit nobody committed\n", Files.readString(repo.resolve("change.txt")));
        assertEquals("a new file\n", Files.readString(repo.resolve("notes.txt")));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3},
                Files.readAllBytes(repo.resolve(".forge-images/mockup.png")));
        assertArrayEquals(log, Files.readAllBytes(bigLog), "a log in pieces comes back whole");
        assertTrue(Shelver.markOf(runs, id).isEmpty());
        assertFalse(Files.readString(repo.resolve(".git/config")).contains("[remote"),
                "the clone's remote went with the shelf");
        Map<String, String> after = heads(remote);
        assertFalse(after.containsKey("shelf/" + FAMILY), "a resumed shelf is off GitHub");
        assertEquals(initial, after.get("main"));
        assertEquals(tip, after.get(branch));
    }

    /** No repository yet: the initial version is built here, and publishing makes the repository. */
    private String firstRun() throws Exception {
        Files.createDirectories(repo);
        new Git(repo, RunPaths.under(runs, FAMILY).gitLog()).init();
        Files.writeString(repo.resolve(MARKER), """
                # Forge self-test

                Made by Forge's LiveGitHubTest, which publishes, shelves and resumes a small family
                here to prove those work against GitHub. Each run adds one enhancement branch.
                Safe to delete whenever you like; the next run makes it again.
                """);
        return commit(FAMILY, "Build (CLAUDE)");
    }

    /**
     * The repository is there: main is cloned as the initial version's history, so it is pushed
     * again at the same commit, and the record Forge would have kept is written as it would be.
     */
    private String laterRun(String url, String repository) throws Exception {
        Files.createDirectories(repo.getParent());
        String problem = ShelfGit.clone(url + ".git", "main", repo, github.credentialHelper(), root.resolve("clone.log"));
        if (problem != null) {
            fail("could not clone main from " + repository + ": " + problem);
        }
        if (!Files.isRegularFile(repo.resolve(MARKER))) {
            fail(repository + " was not made by this test (no " + MARKER + " on main), so it was left alone");
        }
        Git git = new Git(repo, RunPaths.under(runs, FAMILY).gitLog());
        git.init();
        gitIn(repo, "remote", "remove", "origin");
        Files.writeString(RunPaths.under(runs, FAMILY).githubFile(), """
                {"repository":"%s","url":"%s","establishedAt":"%s","branches":[],"outcome":"published","detail":""}
                """.formatted(repository, url, Instant.now()));
        return git.head();
    }

    private String commit(String id, String subject) throws Exception {
        RunPaths paths = RunPaths.under(runs, id);
        Files.createDirectories(paths.logs());
        return new Git(repo, paths.gitLog()).commitAll(subject);
    }

    /** A version as Forge leaves it: its state, and a security check with nothing to rule on. */
    private void version(String id, String title, Phase phase, String finished, String baseline) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle(title);
        state.setPhase(phase);
        state.setFinishedCommit(finished);
        state.setBaselineCommit(baseline);
        RunStore.save(RunPaths.under(runs, id).stateFile(), state);
        new SecurityReport("head", "tree", List.of(LayerResult.ran("secrets", "", List.of())))
                .save(RunPaths.under(runs, id).securityFile());
    }

    /** Every branch GitHub has, and its commit, read with the same credentials Forge pushes with. */
    private Map<String, String> heads(String remote) {
        ProcResult result = CliRunner.run(Path.of("git"), Git.untrusted(List.of(
                        "-c", "credential.helper=", "-c", "credential.helper=" + github.credentialHelper(),
                        "ls-remote", "--heads", remote)),
                root, null, Duration.ofMinutes(2), root.resolve("ls-remote.log"));
        assertTrue(result.ok(), "git ls-remote " + remote + ": " + result.combined());
        Map<String, String> heads = new LinkedHashMap<>();
        result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            String[] parts = line.split("\\s+");
            heads.put(parts[1].replaceFirst("^refs/heads/", ""), parts[0]);
        });
        return heads;
    }

    private void gitIn(Path dir, String... args) {
        ProcResult result = CliRunner.run(Path.of("git"), new ArrayList<>(List.of(args)), dir, null,
                Duration.ofMinutes(1), root.resolve("test-git.log"));
        assertTrue(result.ok(), "git " + String.join(" ", args) + ": " + result.combined());
    }

    private static Set<String> namesIn(Path dir) throws Exception {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
