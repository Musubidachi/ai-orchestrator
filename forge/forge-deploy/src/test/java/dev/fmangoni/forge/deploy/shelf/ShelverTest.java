package dev.fmangoni.forge.deploy.shelf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.deploy.github.FakeGitHub;
import dev.fmangoni.forge.deploy.github.GitHubAccount;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A family put away on GitHub and brought back, with everything Forge keeps for it.
 *
 * <p>The repositories are real and so is every push and clone; only the account is pretend.
 */
class ShelverTest {

    private static final long PART = 1000;
    private static final String REPOSITORY = "gamma-project";

    @TempDir
    Path root;

    private Path runs;
    private Path repo;
    private Git git;
    private String head;
    private String branch;
    private byte[] bigLog;

    @BeforeEach
    void aFamilyMidWay() throws Exception {
        runs = root.resolve("runs");
        repo = RunPaths.under(runs, "003").repo();
        Files.createDirectories(repo);
        Files.createDirectories(RunPaths.under(runs, "003").logs());
        git = new Git(repo, RunPaths.under(runs, "003").gitLog());
        git.init();
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/game.ts"), "export const speed = 1;\n");
        Files.writeString(repo.resolve("old.txt"), "about to go\n");
        git.keepOutOfHistory(".forge-images/");
        git.keepOutOfHistory("artifacts/feature-*");
        head = git.commitAll("Build (CLAUDE)");
        branch = git.currentBranch().orElseThrow();

        // What history does not hold: an edit nobody committed, a new file, a deletion, a picture
        // attached to a note, and a feature recording.
        Files.writeString(repo.resolve("src/game.ts"), "export const speed = 2; // not committed\n");
        Files.writeString(repo.resolve("notes.txt"), "a new file\n");
        Files.delete(repo.resolve("old.txt"));
        Files.createDirectories(repo.resolve(".forge-images"));
        Files.writeString(repo.resolve(".forge-images/mockup.png"), "a picture");
        Files.createDirectories(repo.resolve("artifacts"));
        Files.writeString(repo.resolve("artifacts/feature-race.webm"), "a recording");

        // Forge's own files, outside the repository: a log big enough to go in pieces, the look
        // reference, and a second version of the family with nothing but its run folder.
        bigLog = new byte[2500];
        new Random(4).nextBytes(bigLog);
        Files.write(RunPaths.under(runs, "003").logs().resolve("claude.log"), bigLog);
        Files.writeString(RunPaths.under(runs, "003").lookReference(), "the look");
        version("003", "Gamma Project", Phase.ACCEPTED, false);
        version("003.4", "Saved Filters", Phase.DONE, false);
        Files.createDirectories(RunPaths.under(runs, "003.4").logs());
        Files.writeString(RunPaths.under(runs, "003.4").logs().resolve("gate.log"), "77 passed");
    }

    private void version(String id, String title, Phase phase, boolean midTurn) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle(title);
        state.setPhase(phase);
        state.setTurnInFlight(midTurn);
        RunStore.save(RunPaths.under(runs, id).stateFile(), state);
    }

    private Set<String> namesIn(Path dir) throws Exception {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    @DisplayName("a shelved family is gone from here but for its marks, and comes back exactly as it was")
    void shelvedAndResumed() throws Exception {
        FakeGitHub github = new FakeGitHub(root.resolve("github"), "fabrizio");

        String shelved = new Shelver(runs, "003.4", github, PART).shelve();

        assertTrue(shelved.startsWith("shelved on GitHub as shelf/003"), shelved);
        assertTrue(github.branches(REPOSITORY).containsKey("shelf/003"));
        assertFalse(Files.exists(repo), "the repository is deleted here once GitHub has it");
        assertTrue(Set.of("state.json", "shelved.json", "shelf.log").containsAll(namesIn(RunPaths.under(runs, "003").root())),
                namesIn(RunPaths.under(runs, "003").root()).toString());
        assertEquals(Set.of("state.json", "shelved.json"), namesIn(RunPaths.under(runs, "003.4").root()));
        assertEquals("shelf/003", Shelver.markOf(runs, "003.4").orElseThrow().branch());

        String resumed = new Shelver(runs, "003", github, PART).resume();

        assertEquals("resumed here, and shelf/003 was taken off GitHub", resumed);
        assertFalse(github.branches(REPOSITORY).containsKey("shelf/003"), "a resumed shelf is off GitHub");
        assertEquals(head, git.head(), "back at the project's own last commit");
        assertEquals(Optional.of(branch), git.currentBranch());
        assertEquals("export const speed = 2; // not committed\n", Files.readString(repo.resolve("src/game.ts")));
        assertEquals("a new file\n", Files.readString(repo.resolve("notes.txt")));
        assertFalse(Files.exists(repo.resolve("old.txt")), "deleted before shelving, deleted after");
        assertEquals("a picture", Files.readString(repo.resolve(".forge-images/mockup.png")));
        assertEquals("a recording", Files.readString(repo.resolve("artifacts/feature-race.webm")));
        assertArrayEquals(bigLog, Files.readAllBytes(RunPaths.under(runs, "003").logs().resolve("claude.log")));
        assertEquals("the look", Files.readString(RunPaths.under(runs, "003").lookReference()));
        assertEquals("77 passed", Files.readString(RunPaths.under(runs, "003.4").logs().resolve("gate.log")));
        String config = Files.readString(repo.resolve(".git/config"));
        assertTrue(config.contains("forge@localhost"), "Forge still commits as Forge: " + config);
        assertFalse(config.contains("[remote"), "the clone's remote went with the shelf: " + config);
        assertTrue(Files.readString(repo.resolve(".git/info/exclude")).contains(".forge-images/"));
        assertFalse(Files.exists(repo.resolve(ShelfFiles.DIR)));
        assertFalse(gitOut("log", "--format=%s").contains("Shelved by Forge"), "the shelf never enters the history");
        assertFalse(gitOut("branch", "--list").contains("shelf/"), "nor stays as a branch here");
        assertTrue(Shelver.markOf(runs, "003.4").isEmpty());
    }

    @Test
    @DisplayName("a shelf GitHub never got deletes nothing, and leaves the repository as it was")
    void aFailedPushDeletesNothing() throws Exception {
        GitHubAccount nowhere = new GitHubAccount() {
            @Override
            public Optional<String> login() {
                return Optional.of("fabrizio");
            }

            @Override
            public Optional<String> urlOf(String repository) {
                return Optional.empty();
            }

            @Override
            public String createPrivate(String name, String description) {
                return root.resolve("unreachable.git").toString().replace('\\', '/');
            }

            @Override
            public String credentialHelper() {
                return "";
            }
        };

        String result = new Shelver(runs, "003", nowhere, PART).shelve();

        assertTrue(result.startsWith("not shelved, and nothing was deleted"), result);
        assertEquals(head, git.head());
        assertEquals(Optional.of(branch), git.currentBranch());
        assertFalse(gitOut("branch", "--list").contains("shelf/"));
        assertFalse(Files.exists(repo.resolve(ShelfFiles.DIR)));
        assertEquals("export const speed = 2; // not committed\n", Files.readString(repo.resolve("src/game.ts")),
                "an uncommitted edit survives a shelf that did not happen");
        assertArrayEquals(bigLog, Files.readAllBytes(RunPaths.under(runs, "003").logs().resolve("claude.log")));
        assertTrue(Shelver.markOf(runs, "003").isEmpty());
    }

    @Test
    @DisplayName("a family with a turn in flight is not shelved")
    void notMidTurn() throws Exception {
        version("003.4", "Saved Filters", Phase.REVIEW_REPAIR, true);
        FakeGitHub github = new FakeGitHub(root.resolve("github"), "fabrizio");

        assertEquals("003.4 is in the middle of a turn: shelve it once that stops",
                new Shelver(runs, "003", github, PART).shelve());
        assertEquals(List.of(), github.created);
        assertTrue(Files.exists(repo));
    }

    private String gitOut(String... args) {
        return CliRunner.run(Path.of("git"), List.of(args), repo, null, Duration.ofMinutes(1),
                root.resolve("test-git.log")).stdout();
    }
}
