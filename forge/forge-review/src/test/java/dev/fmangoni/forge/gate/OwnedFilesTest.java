package dev.fmangoni.forge.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A version held to its own folders, with real commits in a real repository. */
class OwnedFilesTest {

    private static final List<String> MARKET = List.of("src/screens/market/**", "e2e/market.spec.ts");

    @TempDir
    Path root;

    private Path repo;
    private Path log;
    private Git git;
    private String baseline;

    @BeforeEach
    void aFamilyWithAShell() throws Exception {
        repo = Files.createDirectories(root.resolve("repo"));
        log = root.resolve("git.log");
        git = new Git(repo, log);
        git.init();
        write("src/shell/App.tsx", "export const App = () => null;\n");
        write("src/design/tokens.ts", "export const gold = '#d4a640';\n");
        git.commitAll("Foundation");
        write("IMPROVEMENT.md", "Build the market screen.\n");
        baseline = git.commitAll("Requested improvement: Market");
    }

    private void write(String rel, String text) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent() == null ? repo : file.getParent());
        Files.writeString(file, text);
    }

    @Test
    @DisplayName("evidence the suite writes is not the version's code, its own or an earlier version's")
    void evidenceIsNotCode() throws Exception {
        write("evidence/shell-home.png", "the foundation's screenshot");
        String start = git.commitAll("Shell evidence");
        write("src/screens/market/Market.tsx", "export const Market = () => null;\n");
        write("evidence/market.png", "this version's picture");
        write("evidence/shell-home.png", "the same screenshot, saved again by the shell's own test");
        git.commitAll("Build");

        assertTrue(OwnedFiles.check(repo, log, start, MARKET).isEmpty(),
                "008.1 changed seven earlier screenshots just by passing their tests");
        assertEquals(List.of("src/design/tokens.ts"),
                OwnedFiles.outside(List.of("evidence/x.png", "src/design/tokens.ts"), MARKET),
                "only evidence: a stray change to code is still caught");
    }

    @Test
    @DisplayName("a version that changed only its own folders passes")
    void ownFoldersPass() throws Exception {
        write("src/screens/market/Market.tsx", "export const Market = () => null;\n");
        write("src/screens/market/parts/Stall.tsx", "export const Stall = () => null;\n");
        write("e2e/market.spec.ts", "test('market', () => {});\n");
        write("artifacts/market.png", "a screenshot the gate saved");
        write("IMPROVEMENT.md", "Build the market screen, as it now reads.\n");
        write("CLAUDE.md", "The house rules, as Forge rewrote them before this turn.\n");
        write("AGENTS.md", "The house rules, as Forge rewrote them before this turn.\n");
        git.commitAll("Continue (session 1)");

        assertEquals(Optional.empty(), OwnedFiles.check(repo, log, baseline, MARKET));
    }

    @Test
    @DisplayName("a change to another feature's code fails, naming each file, what it owns, and how to put it back")
    void anotherFeaturesCodeFails() throws Exception {
        write("src/screens/market/Market.tsx", "export const Market = () => null;\n");
        write("src/design/tokens.ts", "export const gold = '#ffcc00'; // brighter for the market\n");
        write("src/screens/pets/Pets.tsx", "export const Pets = () => null;\n");
        git.commitAll("Continue (session 1)");

        CommandResult failure = OwnedFiles.check(repo, log, baseline, MARKET).orElseThrow();

        assertEquals("owned folders: 2 file(s) outside the folders this version owns", failure.command());
        assertFalse(failure.passed());
        assertTrue(failure.output().contains("  - src/design/tokens.ts"), failure.output());
        assertTrue(failure.output().contains("  - src/screens/pets/Pets.tsx"), failure.output());
        assertFalse(failure.output().contains("  - src/screens/market/Market.tsx"), "its own file is fine");
        assertTrue(failure.output().contains("  - src/screens/market/**"), "says what it owns: " + failure.output());
        assertTrue(failure.output().contains("git checkout " + baseline + " -- <file>"), failure.output());
    }

    @Test
    @DisplayName("deleting another feature's file counts as changing it")
    void aDeletionCounts() throws Exception {
        Files.delete(repo.resolve("src/shell/App.tsx"));
        git.commitAll("Continue (session 1)");

        assertTrue(OwnedFiles.check(repo, log, baseline, MARKET).orElseThrow().output().contains("src/shell/App.tsx"));
    }

    @Test
    @DisplayName("a spec that owns nothing, or a version with no beginning recorded, is not checked")
    void nothingToHoldItTo() throws Exception {
        write("src/design/tokens.ts", "export const gold = '#ffcc00';\n");
        git.commitAll("Continue (session 1)");

        assertEquals(Optional.empty(), OwnedFiles.check(repo, log, baseline, List.of()), "every project built before this");
        assertEquals(Optional.empty(), OwnedFiles.check(repo, log, null, MARKET));
    }

    @Test
    @DisplayName("a beginning that cannot be read is a failure, never a pass")
    void anUnreadableBeginningFails() {
        CommandResult failure = OwnedFiles.check(repo, log, "0000000000000000000000000000000000000000", MARKET).orElseThrow();

        assertTrue(failure.command().startsWith("owned folders: could not read what changed"), failure.command());
    }

    @Test
    @DisplayName("globs: ** crosses folders, * and ? stay in one, and a trailing slash owns the whole folder")
    void globs() {
        assertEquals(List.of(), OwnedFiles.outside(List.of("src/screens/market/a/b/C.tsx", "src/screens/market/M.tsx"),
                List.of("src/screens/market/**")));
        assertEquals(List.of("src/screens/marketplace/M.tsx"), OwnedFiles.outside(List.of("src/screens/marketplace/M.tsx"),
                List.of("src/screens/market/**")), "a folder is not a prefix of its neighbour's name");
        assertEquals(List.of("e2e/deep/market.spec.ts"), OwnedFiles.outside(List.of("e2e/market.spec.ts", "e2e/deep/market.spec.ts"),
                List.of("e2e/*.spec.ts")));
        assertEquals(List.of(), OwnedFiles.outside(List.of("public/themes/halloween/sky.png"), List.of("public/themes/halloween/")));
        assertEquals(List.of(), OwnedFiles.outside(List.of("src/themes/halloween/index.ts", "src/themes/halloween.ts"),
                List.of("src/themes/**/halloween*", "src/themes/halloween/**")));
        assertEquals(List.of(), OwnedFiles.outside(List.of("src\\screens\\market\\M.tsx"), List.of("./src/screens/market/**")),
                "Windows separators and a leading ./ are read as the same path");
    }
}
