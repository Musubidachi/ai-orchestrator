package dev.fmangoni.forge.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.process.CliRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTest {

    /** Any path will do; this is the one Forge keeps out of history. */
    private static final String RECORDINGS = "artifacts/feature-*";

    @TempDir
    Path root;

    private String git(Path repo, String... args) {
        return CliRunner.run(Path.of("git"), List.of(args), repo, null, Duration.ofMinutes(1),
                root.resolve("git.log")).stdout();
    }

    @Test
    @DisplayName("a re-recorded feature clip stops landing in every commit, and stays on disk")
    void recordingsStayOutOfHistory() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Git git = new Git(repo, root.resolve("git.log"));
        git.init();
        Path clip = Files.createDirectories(repo.resolve("artifacts")).resolve("feature-export.webm");
        Files.writeString(clip, "first take");
        Files.writeString(repo.resolve("artifacts/export.webm"), "spec evidence");
        git.commitAll("a turn that committed its recording, as agents here did");

        git.keepOutOfHistory(RECORDINGS);
        git.keepOutOfHistory(RECORDINGS);
        git.commitAll("next turn");
        String afterUntracking = git.head();

        Files.writeString(clip, "the gate recorded it again");
        git.commitAll("the turn after the next gate");

        assertTrue(Files.exists(clip), "the recording itself is left alone");
        assertEquals(List.of("artifacts/export.webm"),
                git(repo, "ls-files", "artifacts").lines().toList(),
                "only the spec's own evidence is still tracked");
        assertFalse(git.changedSince(afterUntracking), "re-recording changed nothing git tracks");
        assertEquals(1, Files.readAllLines(repo.resolve(".git/info/exclude")).stream()
                .filter(RECORDINGS::equals).count(), "excluded once, however often it is asked");
    }

    @Test
    @DisplayName("committing an agent's work runs none of the agent's hooks or fsmonitor")
    void theRepositoryRunsNothingOfItsOwn() throws Exception {
        // The repository is the agent's. A hook it writes, or an fsmonitor command it sets, would
        // otherwise run as Forge the next time a turn is committed — outside any sandbox.
        Path repo = Files.createDirectories(root.resolve("repo"));
        Git git = new Git(repo, root.resolve("git.log"));
        git.init();
        Path marker = root.resolve("ran.txt");
        String script = "#!/bin/sh\necho ran >> '" + marker.toString().replace('\\', '/') + "'\n";
        Files.writeString(repo.resolve(".git/hooks/pre-commit"), script);
        Files.writeString(repo.resolve(".git/hooks/post-commit"), script);
        git(repo, "config", "core.fsmonitor",
                "echo ran >> '" + marker.toString().replace('\\', '/') + "'; exit 1 #");

        Files.writeString(repo.resolve("work.txt"), "what the turn wrote");
        git.commitAll("a turn");

        // Before anything else touches the repository: this test's own plain git calls would run
        // the fsmonitor too, which is rather the point.
        assertFalse(Files.exists(marker), "the repository ran its own code: " + (Files.exists(marker)
                ? Files.readString(marker) : ""));
        assertTrue(git(repo, "ls-files").lines().toList().contains("work.txt"),
                "and it still committed the work");
    }
}
