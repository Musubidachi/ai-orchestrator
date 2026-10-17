package dev.fmangoni.forge.pipeline.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real {@code house-rules.md} and {@code skills/}, as every project receives them.
 *
 * <p>Read from the Forge root rather than a fixture, because what these pin is the shipped text: a
 * skill the rules send an agent to that does not exist, a conditional marker misspelt or left open,
 * or the one deploy rule every project needs ending up inside the deploy-only section, would each
 * reach every project at its next turn without any other test noticing.
 */
class HouseRulesTest {

    /** Tests run from the module folder: forge/forge-pipeline. */
    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static String rules() throws IOException {
        return Files.readString(ROOT.resolve("house-rules.md"));
    }

    @Test
    @DisplayName("every skill the house rules name exists, with the header each CLI needs to find it")
    void everyNamedSkillExists() throws IOException {
        Matcher named = Pattern.compile("`([a-z]+(?:-[a-z]+)+)` skill").matcher(rules());
        List<String> skills = new ArrayList<>();
        while (named.find()) {
            skills.add(named.group(1));
        }
        assertFalse(skills.isEmpty(), "the rules point at their skills by name");
        for (String name : skills) {
            Path skill = ROOT.resolve("skills").resolve(name).resolve("SKILL.md");
            assertTrue(Files.isRegularFile(skill), "the rules send agents to a skill that is not there: " + skill);
            String text = Files.readString(skill).replace("\r\n", "\n");
            assertTrue(text.startsWith("---\nname: " + name + "\ndescription: "), "header of " + skill);
            assertTrue(text.indexOf("\n---\n", 4) > 0, "the header is closed in " + skill);
        }
    }

    @Test
    @DisplayName("every skill folder is one Forge can install: a SKILL.md whose name is its folder's")
    void everySkillFolderIsInstallable() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT.resolve("skills"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String text = Files.readString(dir.resolve("SKILL.md")).replace("\r\n", "\n");
                assertTrue(text.startsWith("---\nname: " + dir.getFileName() + "\n"), "name in " + dir);
                assertTrue(Pattern.compile("\ndescription: .{40,}\n").matcher(text).find(),
                        "a description long enough for a model to know when to load it: " + dir);
            }
        }
    }

    @Test
    @DisplayName("every conditional section is one Forge knows how to decide, and is closed")
    void sectionsAreKnownAndClosed() throws IOException {
        String rules = rules();
        Matcher opened = Pattern.compile("<!-- forge:only ([a-z-]+) -->").matcher(rules);
        int count = 0;
        while (opened.find()) {
            assertTrue(RuleSections.KNOWN.contains(opened.group(1)), "unknown section: " + opened.group(1));
            count++;
        }
        assertEquals(count, rules.split("<!-- forge:end -->", -1).length - 1, "every section closed once");
    }

    @Test
    @DisplayName("a project without ads or deploying loses those pages, never the rule against deploying")
    void aPlainProjectKeepsTheRulesItNeeds() throws IOException {
        String plain = RuleSections.forProject(rules(), Set.of());
        assertFalse(plain.contains("## Ads"), "ads instructions in a project with no ads");
        assertFalse(plain.contains("FORGE_DEPLOY=1"), "deploy instructions in a project with no deploy step");
        assertTrue(plain.contains("**Never run the deploy command, and never log in to anything**"));
        assertTrue(plain.contains("**Never stop processes by name**"));
        assertTrue(plain.contains("**Never commit a credential**"));
        assertFalse(plain.contains("forge:"), "no marker reaches an agent");

        String both = RuleSections.forProject(rules(), Set.of("ads", "deploy"));
        assertTrue(both.contains("## Ads") && both.contains("FORGE_ADS_MODE"));
        assertTrue(both.contains("FORGE_DEPLOY=1"));
    }
}
