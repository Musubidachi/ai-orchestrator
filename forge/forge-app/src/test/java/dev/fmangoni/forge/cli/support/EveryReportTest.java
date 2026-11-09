package dev.fmangoni.forge.cli.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EveryReportTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("an enhancement's page is read from its family's repository, as the pipeline reads it")
    void enhancementReadsItsFamilysRepository() throws IOException {
        // The command built every run's paths from its own folder, and an enhancement has no repository
        // there: its page said the approach was "not in the repository", and lost its changes and pictures.
        ForgeHome home = new ForgeHome(root);
        spec(home, "001", "");
        spec(home, "001.1", "001");
        RunPaths family = RunPaths.under(home.runs(), "001");
        repository(family);
        Files.createDirectories(family.repo().resolve("src"));
        Files.writeString(family.repo().resolve("src/Main.java"), "class Main { int familyMarker; }");
        new Git(family.repo(), family.logs().resolve("git.log")).commitAll("Baseline");
        state(home, "001", Phase.ACCEPTED, null);
        state(home, "001.1", Phase.DONE, "src/Main.java");

        Map<String, String> leftAlone = EveryReport.write(home, RunStates.all(home));

        assertEquals(Map.of(), leftAlone);
        String html = page(RunPaths.under(home.runs(), "001.1"));
        assertTrue(html.contains("familyMarker"), "the approach shown is the family's own class");
        assertFalse(html.contains("which is not in the repository"));
    }

    @Test
    @DisplayName("a project whose repository is not here is left as it is, and the command says why")
    void projectNotHereIsLeftAlone() throws IOException {
        ForgeHome home = new ForgeHome(root);
        spec(home, "004", "");
        state(home, "004", Phase.ACCEPTED, null);

        Map<String, String> leftAlone = EveryReport.write(home, RunStates.all(home));

        assertEquals(Set.of("004"), leftAlone.keySet());
        assertTrue(leftAlone.get("004").startsWith("its repository is not on this machine"), leftAlone.get("004"));
        try (Stream<Path> left = Files.list(RunPaths.under(home.runs(), "004").root())) {
            assertEquals(Set.of("state.json"),
                    left.map(path -> path.getFileName().toString()).collect(Collectors.toSet()),
                    "nothing written, and no git log left behind in the shelf's place");
        }
    }

    @Test
    @DisplayName("a run whose spec has left the wishlist still has its page written from its own folder")
    void runWithoutASpec() throws IOException {
        ForgeHome home = new ForgeHome(root);
        RunPaths paths = RunPaths.under(home.runs(), "007");
        repository(paths);
        state(home, "007", Phase.DONE, null);

        Map<String, String> leftAlone = EveryReport.write(home, RunStates.all(home));

        assertEquals(Map.of(), leftAlone);
        assertTrue(Files.isRegularFile(paths.reportFile()));
    }

    private void repository(RunPaths paths) throws IOException {
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.logs());
        new Git(paths.repo(), paths.logs().resolve("git.log")).init();
    }

    private void spec(ForgeHome home, String id, String parent) throws IOException {
        Files.createDirectories(home.wishlist());
        Files.writeString(home.wishlist().resolve(id + "-spec.md"), """
                ---
                id: "%s"
                %s
                title: Project %s
                priority: 1
                acceptance:
                  - echo ok
                ---
                Do the thing.
                """.formatted(id, parent.isEmpty() ? "" : "parent: \"" + parent + "\"", id));
    }

    private void state(ForgeHome home, String id, Phase phase, String overview) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle("Project " + id);
        state.setPhase(phase);
        if (overview != null) {
            Plan plan = new Plan();
            plan.setOverview(overview);
            state.setPlan(plan);
        }
        RunStore.save(RunPaths.under(home.runs(), id).stateFile(), state);
    }

    private String page(RunPaths paths) throws IOException {
        return Files.readString(paths.reportFile(), StandardCharsets.UTF_8);
    }
}
