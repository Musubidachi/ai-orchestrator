package dev.fmangoni.forge.tour;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.tour.model.Chapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a tour ends.
 *
 * <p>Having heard how the code works, the next thing worth knowing is whether anyone trusts it —
 * and that is a question the source cannot answer. For a project Forge built, the run's own files
 * can, so the closing chapter is the walkthrough: the verdict, what is unproven, what is waiting.
 * The tour must not end by implying a project is finished when its state says otherwise.
 */
class TourSummaryTest {

    @TempDir
    Path runs;

    private Path project(Phase phase) throws IOException {
        RunPaths paths = RunPaths.under(runs, "003");
        Files.createDirectories(paths.repo());
        RunState state = new RunState();
        state.setId("003");
        state.setTitle("Pink Slip Drag Racing");
        state.setPhase(phase);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setSessions(4);
        state.setWorkedMillis(Duration.ofMinutes(140).toMillis());
        RunStore.save(paths.stateFile(), state);
        write(paths.repo().resolve("pom.xml"), """
                <project>
                  <artifactId>drag</artifactId>
                  <modules><module>engine</module></modules>
                </project>
                """);
        write(paths.repo().resolve("engine/src/main/java/demo/Engine.java"), """
                package demo;

                /** Runs a quarter mile. */
                public final class Engine {
                    /** How long the run took. */
                    public int seconds() {
                        return 11;
                    }
                }
                """);
        return paths.repo();
    }

    @Test
    @DisplayName("a tour of a project Forge built ends with that project's walkthrough")
    void theWalkthroughIsTheSummary() throws IOException {
        Chapter last = CodeTour.of(project(Phase.DONE)).chapters().getLast();
        String said = last.scenes().getFirst().said();
        assertTrue(said.contains("Pink Slip Drag Racing"), said);
        assertTrue(said.contains("Every check passed"), said);
    }

    @Test
    @DisplayName("a project nobody reviewed is not summed up as though it were finished")
    void unreviewedIsSaidPlainly() throws IOException {
        String said = CodeTour.of(project(Phase.PARKED)).chapters().getLast()
                .scenes().getFirst().said();
        assertTrue(said.toLowerCase().contains("parked"), said);
    }

    @Test
    @DisplayName("code that is not a run gets what the tour covered, not an invented verdict")
    void anythingElseGetsTheCount(@TempDir Path plain) throws IOException {
        write(plain.resolve("pom.xml"), """
                <project><modules><module>bits</module></modules></project>
                """);
        write(plain.resolve("bits/src/main/java/demo/Bit.java"), """
                package demo;

                /** One bit. */
                public final class Bit {
                    /** Its value. */
                    public boolean set() {
                        return true;
                    }
                }
                """);
        String said = CodeTour.of(plain).chapters().getLast().scenes().getFirst().said();
        assertTrue(said.contains("1 class"), said);
        assertTrue(said.contains("came from the code"), said);
    }

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
