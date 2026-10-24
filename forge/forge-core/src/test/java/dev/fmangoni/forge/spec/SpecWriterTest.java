package dev.fmangoni.forge.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.model.DeploySettings;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.MonetizationSettings;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The round trip is the property that matters: anything the UI writes must load back through the
 * same {@link SpecLoader} the pipeline uses, or the form will cheerfully produce files that Forge
 * refuses at 2am.
 */
class SpecWriterTest {

    @TempDir
    Path dir;

    private FrontMatter front(String id, String title) {
        FrontMatter front = new FrontMatter();
        front.setId(id);
        front.setTitle(title);
        front.setAcceptance(List.of("mvn -q test"));
        return front;
    }

    @Test
    @DisplayName("what the writer produces, the loader accepts")
    void roundTrips() {
        FrontMatter front = front("001", "Crochet pattern differ");
        front.setAssign("claude");
        front.setEvidence(List.of("out.html"));
        front.setMaxRepairRounds(2);

        Path file = SpecWriter.write(dir, front, "## Goal\nCompare two patterns.");
        Spec reloaded = SpecLoader.load(file);

        assertEquals("001", reloaded.id());
        assertEquals("Crochet pattern differ", reloaded.title());
        assertEquals(List.of("mvn -q test"), reloaded.acceptance());
        assertEquals(List.of("out.html"), reloaded.evidence());
        assertEquals(2, reloaded.maxRepairRounds());
        assertTrue(reloaded.body().startsWith("## Goal"));
    }

    @Test
    @DisplayName("serve, deploy and monetization survive the round trip, and stay out of specs without them")
    void outwardFieldsRoundTrip() throws Exception {
        FrontMatter plain = front("001", "Plain");
        String plainYaml = Files.readString(SpecWriter.write(dir, plain, "Build it."));
        for (String key : List.of("serve", "deploy", "monetization")) {
            assertTrue(!plainYaml.contains(key + ":"), key + " written into a spec that never set it:\n"
                    + plainYaml);
        }

        FrontMatter front = front("002", "Invoice tool");
        front.setServe("npm run preview -- --port {port}");
        DeploySettings deploy = new DeploySettings();
        deploy.setCommand("npx wrangler pages deploy dist");
        deploy.setUrl("https://invoice-tool.pages.dev");
        deploy.setRequires(List.of("npx"));
        front.setDeploy(deploy);
        MonetizationSettings money = new MonetizationSettings();
        money.setAds("test");
        money.setProvider("adsense");
        money.setIds(new LinkedHashMap<>(Map.of("publisher", "ca-pub-123")));
        front.setMonetization(money);

        Spec reloaded = SpecLoader.load(SpecWriter.write(dir, front, "Build it."));

        assertEquals(Optional.of("npm run preview -- --port {port}"), reloaded.serve());
        assertEquals("/", reloaded.servePath());
        assertEquals("npx wrangler pages deploy dist", reloaded.deploy().orElseThrow().getCommand());
        assertEquals(List.of("npx"), reloaded.deploy().orElseThrow().getRequires());
        assertEquals("test", reloaded.adsMode());
        assertEquals("ca-pub-123", reloaded.front().getMonetization().getIds().get("publisher"));
    }

    @Test
    @DisplayName("the design an enhancement was built to survives the round trip, and stays out of every other spec")
    void designRoundTrips() throws Exception {
        Path plain = SpecWriter.write(dir, front("001", "Plain"), "Build it.");
        String plainYaml = Files.readString(plain);
        assertTrue(!plainYaml.contains("design:"), "written into a spec that never set it:\n" + plainYaml);
        assertEquals(Optional.empty(), SpecLoader.load(plain).design());

        FrontMatter front = front("001.1", "Plain visual update");
        front.setDesign("Neon: arcade");

        assertEquals(Optional.of("Neon: arcade"), SpecLoader.load(SpecWriter.write(dir, front, "Restyle it.")).design());
    }

    @Test
    @DisplayName("a title containing a colon does not corrupt the YAML")
    void quotesAwkwardTitles() {
        Path file = SpecWriter.write(dir, front("002", "Differ: compares two things"), "Build it.");

        assertEquals("Differ: compares two things", SpecLoader.load(file).title());
    }

    @Test
    @DisplayName("acceptance commands with quotes survive the round trip verbatim")
    void preservesQuotedAcceptanceCommands() {
        FrontMatter front = front("003", "Quoted");
        String command = "mvn -q exec:java -Dexec.args=\"a.pdf b.pdf out.html\"";
        front.setAcceptance(List.of(command));

        Path file = SpecWriter.write(dir, front, "Build it.");

        assertEquals(List.of(command), SpecLoader.load(file).acceptance());
    }

    @Test
    @DisplayName("multi-line prose with headings and code fences comes back unchanged")
    void preservesProse() {
        String body = """
                ## Goal
                Do the thing.

                ## Notes
                Use this shape:

                ```java
                var x = new Thing();
                ```""";

        Path file = SpecWriter.write(dir, front("004", "Prose"), body);

        assertEquals(body, SpecLoader.load(file).body());
    }

    @Test
    @DisplayName("per-spec models survive the round trip and reach the right vendor")
    void roundTripsModels() {
        FrontMatter front = front("005", "Model choice");
        front.setClaudeModel("opus");
        front.setCodexModel("gpt-6-astra");

        Spec reloaded = SpecLoader.load(SpecWriter.write(dir, front, "Build it."));

        assertEquals("opus", reloaded.modelFor(AgentKind.CLAUDE));
        assertEquals("gpt-6-astra", reloaded.modelFor(AgentKind.CODEX));
    }

    @Test
    @DisplayName("an unset model stays empty, so the CLI default is left alone")
    void absentModelMeansTheCliDefault() {
        Spec reloaded = SpecLoader.load(SpecWriter.write(dir, front("006", "No choice"), "Build."));

        assertEquals("", reloaded.modelFor(AgentKind.CLAUDE));
        assertEquals("", reloaded.modelFor(AgentKind.CODEX));
    }

    @Test
    void namesFilesFromIdAndTitle() {
        assertEquals("001-crochet-pattern-differ.md",
                SpecWriter.fileName(front("001", "Crochet pattern differ")));
    }

    @Test
    @DisplayName("punctuation and spacing never reach the filename")
    void slugifiesAwkwardTitles() {
        assertEquals("002-differ-compares-a-b.md",
                SpecWriter.fileName(front("002", "Differ: compares A & B!")));
    }
}
