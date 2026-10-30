package dev.fmangoni.forge.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A version held to its folders, failing on a check it cannot change.
 *
 * <p>The outputs are cut from 008.1's real gate: vitest naming the foundation's scene test, which
 * pinned the always-day placeholder that 008.1 was there to replace.
 */
class ForeignFailureTest {

    private static final Path REPO = Path.of("C:/repos/ai-orchestrator/runs/008/repo");
    private static final List<String> LIGHTING = List.of("src/lighting/**", "e2e/lighting/**");
    private static final Set<String> TRACKED = Set.of("tests/scene.test.ts", "src/lighting/index.ts",
            "e2e/scene/scene.spec.ts", "package.json");

    private static final String ALWAYS_DAY = """
             RUN  v5.0.5 C:/repos/ai-orchestrator/runs/008/repo

             ❯ tests/scene.test.ts (5 tests | 1 failed) 21ms
               × is always day in this version 10ms
             FAIL  tests/scene.test.ts > lighting > is always day in this version
            AssertionError: expected 'night' to be 'day'
             ❯ tests/scene.test.ts:28:56
                 28|     expect(lighting.at(new Date(2026, 0, 1, 3)).phase).toBe('day');
              at node_modules/vitest/dist/chunk.js:12:3
            """;

    private static CommandResult failed(String command, String output) {
        return new CommandResult(command, 1, output, false, Duration.ofSeconds(1), "gate-1.bat");
    }

    private static Optional<String> stuck(CommandResult before, CommandResult now) {
        return ForeignFailure.stuck(REPO, TRACKED, LIGHTING, before, now);
    }

    @Test
    @DisplayName("the same failure twice, only in a file the version does not own, waits for a person")
    void stuckOnAnotherVersionsTest() {
        Optional<String> why = stuck(failed("npm test", ALWAYS_DAY), failed("npm test", ALWAYS_DAY));

        assertTrue(why.isPresent());
        assertTrue(why.get().contains("tests/scene.test.ts"), why.get());
        assertTrue(why.get().contains("does not own"), why.get());
    }

    @Test
    @DisplayName("the first failure gets its repair: the version may have broken that test through its own code")
    void theFirstFailureIsRepaired() {
        assertEquals(Optional.empty(), stuck(null, failed("npm test", ALWAYS_DAY)));
    }

    @Test
    @DisplayName("a failure that names one of the version's own files may be its own to fix")
    void anOwnedFileNamedKeepsRepairing() {
        String own = ALWAYS_DAY + " at src/lighting/index.ts:4:10\n";

        assertEquals(Optional.empty(), stuck(failed("npm test", own), failed("npm test", own)));
    }

    @Test
    @DisplayName("a different command, or a different file, is a new failure and gets its repair")
    void aChangedFailureIsNew() {
        String e2e = "  1) [chromium] › e2e/scene/scene.spec.ts:14:7 › scene engine › layers match\n";

        assertEquals(Optional.empty(), stuck(failed("npm run build", ALWAYS_DAY), failed("npm test", ALWAYS_DAY)));
        assertEquals(Optional.empty(), stuck(failed("npm test", e2e), failed("npm test", ALWAYS_DAY)));
    }

    @Test
    @DisplayName("a version with no owned folders, or a failure naming no file, is never held this way")
    void onlyForVersionsHeldToFolders() {
        assertEquals(Optional.empty(), ForeignFailure.stuck(REPO, TRACKED, List.of(),
                failed("npm test", ALWAYS_DAY), failed("npm test", ALWAYS_DAY)));
        assertEquals(Optional.empty(), stuck(failed("npm test", "Killed"), failed("npm test", "Killed")));
    }

    @Test
    @DisplayName("files are named from the repository root, whether written relative, absolute or with backslashes")
    void filesAreNamedFromTheRoot() {
        String windows = "Error in C:\\repos\\ai-orchestrator\\runs\\008\\repo\\e2e\\scene\\scene.spec.ts:22\n";

        assertEquals(Set.of("tests/scene.test.ts"), ForeignFailure.named(REPO, TRACKED, failed("npm test", ALWAYS_DAY)),
                "node_modules is not tracked, so a stack frame there names nothing");
        assertEquals(Set.of("e2e/scene/scene.spec.ts"), ForeignFailure.named(REPO, TRACKED, failed("x", windows)));
    }
}
