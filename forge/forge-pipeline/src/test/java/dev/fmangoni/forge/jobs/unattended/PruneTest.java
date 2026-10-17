package dev.fmangoni.forge.jobs.unattended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The only code in Forge that deletes anything.
 *
 * <p>It runs unattended, on a schedule, on a machine whose other directories hold the user's real
 * repositories. So the tests worth having are the ones about what it must <em>not</em> touch: the
 * git history, the evidence, the report, anything still running, anything finished too recently,
 * and above all anything outside a run directory.
 */
class PruneTest {

    @TempDir
    Path root;

    private static final Duration ONE_DAY = Duration.ofDays(1);

    /** A run with build output, a git history, evidence and a report — the full shape. */
    private RunPaths runOf(String id, Phase phase, Instant updated) throws IOException {
        RunPaths paths = RunPaths.under(root.resolve("runs"), id);
        Path repo = paths.repo();
        Files.createDirectories(repo.resolve("target/classes"));
        Files.createDirectories(repo.resolve("node_modules/left-pad"));
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.createDirectories(repo.resolve(".git"));

        Files.writeString(repo.resolve("target/classes/Main.class"), "x".repeat(500));
        Files.writeString(repo.resolve("node_modules/left-pad/index.js"), "y".repeat(300));
        Files.writeString(repo.resolve("src/main/java/Main.java"), "source");
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.createDirectories(paths.evidence());
        Files.writeString(paths.evidence().resolve("shot.png"), "png");
        Files.writeString(paths.reportFile(), "<h1>report</h1>");

        RunState state = new RunState();
        state.setId(id);
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        RunStore.save(paths.stateFile(), state);
        // RunStore stamps updatedAt on save, so the age is set afterwards.
        RunState saved = RunStore.load(paths.stateFile()).orElseThrow();
        saved.setUpdatedAt(updated);
        writeRaw(paths.stateFile(), saved);
        return paths;
    }

    private void writeRaw(Path file, RunState state) throws IOException {
        // Bypasses RunStore.save, which would re-stamp updatedAt to now.
        Files.writeString(file, new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(state), StandardCharsets.UTF_8);
    }

    private static Instant longAgo() {
        return Instant.now().minus(30, ChronoUnit.DAYS);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a junction inside node_modules is removed, and what it points at is not")
    void junctionsAreNotFollowed() throws Exception {
        // Measured on JDK 24: Files.walk goes straight through an NTFS junction and lists the files
        // on the far side, so the old prune deleted the originals. npm workspaces link packages back
        // to their source this way, and npm link and pnpm link to folders anywhere on the disk.
        RunPaths paths = runOf("001", Phase.ACCEPTED, longAgo());
        Path elsewhere = Files.createDirectories(root.resolve("somebody-else/linked-package"));
        Files.writeString(elsewhere.resolve("index.js"), "source code that is not ours");
        Path link = paths.repo().resolve("node_modules/linked-package");

        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), elsewhere.toString()).redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        assertEquals(0, mklink.waitFor(), "could not create a junction to test with");

        Prune.run(root.resolve("runs"), ONE_DAY);

        assertFalse(Files.exists(paths.repo().resolve("node_modules"),
                LinkOption.NOFOLLOW_LINKS), "the build output was not pruned");
        assertTrue(Files.exists(elsewhere.resolve("index.js")),
                "pruning a project deleted files outside it, through a junction");
    }

    @Test
    @DisplayName("an unreadable state file skips that run, not every run after it")
    void unreadableStateDoesNotStopThePrune() throws IOException {
        RunPaths good = runOf("002", Phase.ACCEPTED, longAgo());
        Path broken = RunPaths.under(root.resolve("runs"), "001").stateFile();
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{\"id\":\"001\",\"pha");

        Prune.run(root.resolve("runs"), ONE_DAY);

        assertFalse(Files.exists(good.repo().resolve("target")));
    }

    @Test
    @DisplayName("build output goes from a finished, aged run")
    void deletesBuildOutput() throws IOException {
        RunPaths paths = runOf("001", Phase.ACCEPTED, longAgo());

        long freed = Prune.run(root.resolve("runs"), ONE_DAY);

        assertFalse(Files.exists(paths.repo().resolve("target")));
        assertFalse(Files.exists(paths.repo().resolve("node_modules")));
        assertEquals(800, freed, "reports the bytes it actually reclaimed");
    }

    @Test
    @DisplayName("the history, the evidence, the report and the source all survive")
    void keepsEverythingWorthKeeping() throws IOException {
        RunPaths paths = runOf("001", Phase.ACCEPTED, longAgo());

        Prune.run(root.resolve("runs"), ONE_DAY);

        assertTrue(Files.exists(paths.repo().resolve(".git/HEAD")), "a pruned project must still be inspectable");
        assertTrue(Files.exists(paths.repo().resolve("src/main/java/Main.java")));
        assertTrue(Files.exists(paths.evidence().resolve("shot.png")));
        assertTrue(Files.exists(paths.reportFile()));
        assertTrue(Files.exists(paths.stateFile()));
    }

    @Test
    @DisplayName("a run that is still working is left completely alone")
    void skipsUnfinishedRuns() throws IOException {
        RunPaths paths = runOf("001", Phase.CONTINUING, longAgo());

        assertEquals(0, Prune.run(root.resolve("runs"), ONE_DAY));
        assertTrue(Files.exists(paths.repo().resolve("target/classes/Main.class")));
    }

    @Test
    @DisplayName("a run that finished recently keeps its build output")
    void respectsTheAgeThreshold() throws IOException {
        // Just finished: the build output is exactly what you want if you go and look at it now.
        RunPaths paths = runOf("001", Phase.DONE, Instant.now().minus(2, ChronoUnit.HOURS));

        assertEquals(0, Prune.run(root.resolve("runs"), ONE_DAY));
        assertTrue(Files.exists(paths.repo().resolve("target")));
    }

    @Test
    @DisplayName("a parked run is pruned too — it is finished with, however it ended")
    void prunesParkedRuns() throws IOException {
        RunPaths paths = runOf("001", Phase.PARKED, longAgo());

        Prune.run(root.resolve("runs"), ONE_DAY);

        assertFalse(Files.exists(paths.repo().resolve("target")));
        assertTrue(Files.exists(paths.reportFile()),
                "the report explaining why it parked is the whole point of keeping it");
    }

    @Test
    @DisplayName("a directory that is not a run is ignored, not walked")
    void ignoresNonRuns() throws IOException {
        Path stray = root.resolve("runs/not-a-run");
        Files.createDirectories(stray.resolve("target"));
        Files.writeString(stray.resolve("target/important.txt"), "not ours to delete");

        assertEquals(0, Prune.run(root.resolve("runs"), ONE_DAY));
        assertTrue(Files.exists(stray.resolve("target/important.txt")),
                "without a state file there is nothing saying this is a finished Forge run");
    }

    @Test
    @DisplayName("nothing outside a run's own repository is reachable")
    void staysInsideTheRun() throws IOException {
        // A sibling that looks exactly like a prunable target directory, one level up from where
        // Prune is allowed to look. If the path handling is ever wrong, this is what it eats.
        Path sibling = root.resolve("target");
        Files.createDirectories(sibling);
        Files.writeString(sibling.resolve("keep.txt"), "someone else's build output");
        runOf("001", Phase.ACCEPTED, longAgo());

        Prune.run(root.resolve("runs"), ONE_DAY);

        assertTrue(Files.exists(sibling.resolve("keep.txt")));
    }

    @Test
    @DisplayName("a missing runs directory is a no-op rather than a crash")
    void missingRunsDirIsHarmless() {
        assertEquals(0, Prune.run(root.resolve("nothing-here"), ONE_DAY));
    }
}
