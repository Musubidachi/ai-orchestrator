package dev.fmangoni.forge.deploy.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchPushTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a branch that moved on without us is left as it is: Forge never force-pushes")
    void neverForces() throws Exception {
        FakeGitHub github = new FakeGitHub(root.resolve("github"), "fabrizio");
        String url = github.createPrivate("game", "");
        Path repo = Files.createDirectories(root.resolve("repo"));
        Git git = new Git(repo, root.resolve("git.log"));
        git.init();
        String first = git.commitAll("first");
        String second = git.commitAll("second");
        BranchPush push = new BranchPush(repo, root.resolve("push.log"));

        assertNull(push.push(url, second, "main", ""));
        String problem = push.push(url, first, "main", "");

        assertTrue(problem.startsWith("main on GitHub has commits this one does not"), problem);
        assertEquals(second, github.branches("game").get("main"), "what was there is still there");
    }
}
