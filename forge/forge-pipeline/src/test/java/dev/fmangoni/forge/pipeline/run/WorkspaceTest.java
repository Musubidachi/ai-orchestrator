package dev.fmangoni.forge.pipeline.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path root;

    private Spec spec(String extra) throws Exception {
        return SpecLoader.load(Files.writeString(root.resolve("001.md"), """
                ---
                id: "001"
                title: App
                acceptance:
                  - echo ok
                %s
                ---
                Build it.
                """.formatted(extra)));
    }

    @Test
    @DisplayName("the agent reads this project's serve, deploy and ads settings, refreshed every turn")
    void settingsReachTheAgent() throws Exception {
        Spec spec = spec("""
                serve: npm run preview -- --port {port}
                deploy:
                  command: npx wrangler pages deploy dist
                monetization:
                  ads: live
                  provider: adsense
                  ids: { publisher: ca-pub-1 }""");
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());

        new Workspace(paths, "# House rules\n").refreshRules(spec);

        String rules = Files.readString(paths.repo().resolve("AGENTS.md"));
        assertTrue(rules.startsWith("# House rules"), rules);
        assertTrue(rules.contains("npm run preview -- --port {port}"), rules);
        assertTrue(rules.contains("never by you, with: `npx wrangler pages deploy dist`"), rules);
        assertTrue(rules.contains("mode `live` in deploys, test slots everywhere else"), rules);
        assertTrue(rules.contains("`FORGE_AD_PUBLISHER`"), rules);
        assertEquals(rules, Files.readString(paths.repo().resolve("CLAUDE.md")));
    }

    @Test
    @DisplayName("a project with none of those settings gets the house rules unchanged")
    void noSettingsNoSection() throws Exception {
        assertEquals("", Workspace.projectSettings(spec("")));
    }

    @Test
    @DisplayName("a spec changed after the project started is brought up to date, committed, and the change returned")
    void aChangedSpecIsRefreshed() throws Exception {
        // 003: the spec was edited to allow a rewarded ad that granted an item, and six sessions read the
        // copy made when the project began, which still forbade it.
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Workspace workspace = new Workspace(paths, "# House rules\n");
        Spec original = spec("");
        workspace.scaffold(original);
        assertTrue(workspace.refreshSpec(original, original.acceptance()).isEmpty(), "nothing changed, nothing to say");

        Spec edited = SpecLoader.load(Files.writeString(root.resolve("001.md"),
                Files.readString(root.resolve("001.md")).replace("Build it.",
                        "Build it, with an offer of one extra credit for watching an ad.")));
        Optional<SpecChange> change = workspace.refreshSpec(edited, edited.acceptance());

        assertTrue(change.isPresent());
        assertEquals("SPEC.md", change.get().file());
        assertTrue(change.get().diff().contains("+Build it, with an offer"), change.get().diff());
        assertTrue(change.get().diff().contains("-Build it."), change.get().diff());
        assertTrue(Files.readString(paths.repo().resolve("SPEC.md")).contains("an offer of one extra credit"));
        assertFalse(workspace.git().isDirty(), "committed in its own right, before the agent touches anything");
        assertTrue(workspace.refreshSpec(edited, edited.acceptance()).isEmpty(), "told once, not every turn");
    }

    private static final String SECTIONED = """
            # House rules
            Never commit a credential.
            <!-- forge:only ads -->
            ## Ads
            Read the mode from FORGE_ADS_MODE.
            <!-- forge:end -->
            <!-- forge:only deploy -->
            ## Deployment
            Make it deployable.
            <!-- forge:end -->
            <!-- forge:only typo -->
            A rule behind a marker Forge does not know.
            <!-- forge:end -->
            ## Scope
            Build what SPEC.md asks for.
            """;

    @Test
    @DisplayName("a section for ads or deploying reaches only the projects that have them")
    void conditionalSectionsFollowTheSpec() throws Exception {
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());

        new Workspace(paths, SECTIONED).refreshRules(spec(""));
        String plain = Files.readString(paths.repo().resolve("AGENTS.md"));
        assertFalse(plain.contains("FORGE_ADS_MODE"), plain);
        assertFalse(plain.contains("Make it deployable"), plain);
        assertTrue(plain.contains("Never commit a credential.") && plain.contains("Build what SPEC.md asks for."), plain);
        assertTrue(plain.contains("A rule behind a marker Forge does not know."), "an unknown marker never hides a rule");
        assertFalse(plain.contains("forge:"), "no marker reaches the agent: " + plain);

        new Workspace(paths, SECTIONED).refreshRules(spec("""
                deploy:
                  command: npx wrangler pages deploy dist
                monetization:
                  ads: test"""));
        String both = Files.readString(paths.repo().resolve("CLAUDE.md"));
        assertTrue(both.contains("## Ads\nRead the mode from FORGE_ADS_MODE.") || both.contains("## Ads\r\nRead the mode"), both);
        assertTrue(both.contains("Make it deployable."), both);

        new Workspace(paths, SECTIONED).refreshRules();
        String unknown = Files.readString(paths.repo().resolve("AGENTS.md"));
        assertTrue(unknown.contains("FORGE_ADS_MODE") && unknown.contains("Make it deployable"),
                "with no spec to decide by, nothing is dropped");
    }

    private Path skillsSource() throws Exception {
        Path source = root.resolve("skills");
        Files.createDirectories(source.resolve("record-feature"));
        Files.writeString(source.resolve("record-feature/SKILL.md"), "---\nname: record-feature\ndescription: d\n---\nHow.\n");
        Files.writeString(source.resolve("record-feature/GifSequenceWriter.java"), "class GifSequenceWriter {}\n");
        Files.createDirectories(source.resolve("run-bat"));
        Files.writeString(source.resolve("run-bat/SKILL.md"), "---\nname: run-bat\ndescription: d\n---\nTemplates.\n");
        Files.createDirectories(source.resolve("notes-not-a-skill"));
        Files.writeString(source.resolve("notes-not-a-skill/README.md"), "no SKILL.md here\n");
        return source;
    }

    @Test
    @DisplayName("the skills are installed where every vendor looks, with their helpers, and kept out of git")
    void skillsAreInstalledForEveryVendor() throws Exception {
        Path source = skillsSource();
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Workspace workspace = new Workspace(paths, "# House rules\n", source);
        workspace.scaffold(spec(""));

        for (String folder : List.of(".claude/skills", ".agents/skills")) {
            Path installed = paths.repo().resolve(folder);
            assertTrue(Files.readString(installed.resolve("record-feature/SKILL.md")).contains("How."), folder);
            assertTrue(Files.exists(installed.resolve("record-feature/GifSequenceWriter.java")), "helpers travel with the skill: " + folder);
            assertTrue(Files.exists(installed.resolve("run-bat/SKILL.md")), folder);
            assertFalse(Files.exists(installed.resolve("notes-not-a-skill")), "a folder without a SKILL.md is not a skill");
        }
        assertTrue(workspace.git().files().stream().noneMatch(f -> f.startsWith(".claude/") || f.startsWith(".agents/")),
                "Forge's own files must not land in the project's history: " + workspace.git().files());
        assertFalse(workspace.git().isDirty(), "nothing left for the next commit to pick up");
    }

    @Test
    @DisplayName("an edited skill reaches a project under way, a removed one leaves it, and a project's own stays")
    void skillsFollowForgeAndLeaveTheProjectsOwnAlone() throws Exception {
        Path source = skillsSource();
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Workspace workspace = new Workspace(paths, "# House rules\n", source);
        workspace.scaffold(spec(""));
        Path claude = paths.repo().resolve(".claude/skills");
        Files.createDirectories(claude.resolve("projects-own"));
        Files.writeString(claude.resolve("projects-own/SKILL.md"), "mine\n");

        Files.writeString(source.resolve("record-feature/SKILL.md"), "---\nname: record-feature\ndescription: d\n---\nHow, revised.\n");
        Files.delete(source.resolve("run-bat/SKILL.md"));
        Files.delete(source.resolve("run-bat"));
        workspace.refreshRules(spec(""));

        assertTrue(Files.readString(claude.resolve("record-feature/SKILL.md")).contains("revised"));
        assertFalse(Files.exists(claude.resolve("run-bat")), "a skill Forge no longer has is taken back out");
        assertFalse(Files.exists(paths.repo().resolve(".agents/skills/run-bat")));
        assertEquals("mine\n", Files.readString(claude.resolve("projects-own/SKILL.md")), "never touch what Forge did not put there");
    }

    @Test
    @DisplayName("before a project has started there is no copy to bring up to date")
    void nothingToRefreshBeforeTheStart() throws Exception {
        RunPaths paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());

        assertTrue(new Workspace(paths, "# House rules\n").refreshSpec(spec(""), List.of("echo ok")).isEmpty());
        assertFalse(Files.exists(paths.repo().resolve("SPEC.md")));
    }
}
