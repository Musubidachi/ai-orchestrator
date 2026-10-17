package dev.fmangoni.forge.security.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisTest {

    @TempDir
    Path root;

    /** Semgrep is not installed here, and the shell says so. */
    private static final Shell NO_SEMGREP = (name, command) -> new ProcResult(9009, "",
            "'semgrep' is not recognized as an internal or external command", false, Duration.ZERO);

    private void write(Path repo, String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("risky patterns are noted, constants and test code are not, and nothing is serious")
    void builtInRules() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Git git = new Git(repo, root.resolve("git.log"));
        git.init();
        write(repo, "src/ui/board.ts", """
                panel.innerHTML = "<b>static</b>";
                panel.innerHTML = `<p>${player.name}</p>`;
                const run = eval(userFormula);
                """);
        write(repo, "src/main/java/Store.java",
                "stmt.executeQuery(\"SELECT * FROM cars WHERE owner = '\" + owner + \"'\");\n");
        write(repo, "src/app.py", "data = yaml.load(stream)\nsafe = yaml.load(stream, Loader=yaml.SafeLoader)\n");
        write(repo, "src/ui/board.test.ts", "expect(eval('1+1')).toBe(2);\n");
        git.commitAll("code");

        LayerResult result = new StaticAnalysis(repo, git, NO_SEMGREP, root.resolve("security")).run();

        List<String> where = result.findings().stream().map(SecurityFinding::location).toList();
        assertEquals(List.of("src/app.py:1", "src/main/java/Store.java:1", "src/ui/board.ts:2", "src/ui/board.ts:3"),
                where.stream().sorted().toList(), result.findings().toString());
        assertTrue(result.findings().stream().noneMatch(SecurityFinding::serious));
        assertTrue(result.detail().contains("semgrep is not installed"), result.detail());
        assertFalse(where.contains("src/ui/board.test.ts:1"));
    }
}
