package dev.fmangoni.forge.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A version built to pictures shows each one beside its screenshot. */
class PicturesInTheReportTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private RunState state;

    private static final String TABLE = """
            | Picture | State | Your screenshot of it |
            |---|---|---|
            | [image: .forge-images/landing.png] | The event page | `artifacts/landing.png` |
            | [image: .forge-images/shop.png] | The shop & its prices | `artifacts/shop.png` |
            """;

    @BeforeEach
    void setUp() throws IOException {
        // A family's version: its own run folder, working in the family's repository.
        paths = new RunPaths(root.resolve("runs/001.2"), root.resolve("runs/001/repo"));
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.evidence());
        state = new RunState();
        state.setId("001.2");
        state.setTitle("The event");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);
    }

    private void file(String relative) throws IOException {
        Path file = paths.repo().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
    }

    private String report() throws IOException {
        new ProjectReport(paths, state, null).write();
        return Files.readString(paths.reportFile(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("each picture is shown beside its screenshot, and a missing screenshot says so")
    void pairsEachRow() throws IOException {
        Files.writeString(paths.repo().resolve("IMPROVEMENT.md"),
                "# Improvement: The event\r\n\r\nBuild it.\r\n\r\n" + TABLE.replace("\n", "\r\n"));
        file(".forge-images/landing.png");
        file(".forge-images/shop.png");
        file("artifacts/landing.png");

        String html = report();

        assertTrue(html.contains("id=\"pictures\""), html);
        assertTrue(html.contains("<img src=\"../001/repo/.forge-images/landing.png\""), html);
        assertTrue(html.contains("<img src=\"../001/repo/artifacts/landing.png\""), html);
        assertTrue(html.contains("<h3>The shop &amp; its prices</h3>"), html);
        assertTrue(html.contains("No screenshot yet."), "the shop's screenshot is not there");
    }

    @Test
    @DisplayName("another version's request is not shown as this one's")
    void notAnotherVersionsPictures() throws IOException {
        Files.writeString(paths.repo().resolve("IMPROVEMENT.md"),
                "# Improvement: A later event\n\n" + TABLE);
        file(".forge-images/landing.png");

        assertFalse(report().contains("id=\"pictures\""));
    }

    @Test
    @DisplayName("a project's own spec, with no pictures table, adds nothing")
    void nothingWithoutATable() throws IOException {
        Files.writeString(paths.repo().resolve("SPEC.md"), "# The event\n\nNo pictures here.\n");

        assertFalse(report().contains("id=\"pictures\""));
    }
}
