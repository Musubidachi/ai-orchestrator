package dev.fmangoni.forge.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpecLoaderTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    private static final String VALID = """
            ---
            id: "001"
            title: Crochet pattern differ
            assign: claude
            acceptance:
              - mvn -q test
              - mvn -q exec:java -Dexec.args="a.pdf b.pdf out.html"
            evidence:
              - out.html
            max_repair_rounds: 2
            ---

            ## Goal
            Compare two crochet patterns and render the differences.
            """;

    @Nested
    @DisplayName("a well-formed spec")
    class Valid {

        @Test
        void readsTheContractAndTheProse() throws IOException {
            Spec spec = SpecLoader.load(write("001-differ.md", VALID));

            assertEquals("001", spec.id());
            assertEquals("Crochet pattern differ", spec.title());
            assertEquals(Optional.of(AgentKind.CLAUDE), spec.assignedAgent());
            assertEquals(List.of("out.html"), spec.evidence());
            assertTrue(spec.body().startsWith("## Goal"), spec.body());
        }

        @Test
        @DisplayName("acceptance commands survive verbatim, quotes and all")
        void keepsAcceptanceCommandsIntact() throws IOException {
            Spec spec = SpecLoader.load(write("001-differ.md", VALID));

            assertEquals(
                    List.of("mvn -q test", "mvn -q exec:java -Dexec.args=\"a.pdf b.pdf out.html\""),
                    spec.acceptance());
        }

        @Test
        @DisplayName("snake_case keys map onto camelCase fields")
        void mapsSnakeCaseKeys() throws IOException {
            assertEquals(2, SpecLoader.load(write("001-differ.md", VALID)).maxRepairRounds());
        }

        @Test
        void appliesDefaultsWhenOmitted() throws IOException {
            Spec spec = SpecLoader.load(write("002-min.md", """
                    ---
                    id: "002"
                    title: Minimal
                    acceptance:
                      - mvn -q test
                    ---
                    Build the thing.
                    """));

            assertEquals(Optional.empty(), spec.assignedAgent(), "absent 'assign' means auto");
            assertEquals(4, spec.maxRepairRounds());
            assertEquals(30, spec.agentTimeout().toMinutes());
        }
    }

    @Nested
    @DisplayName("a spec Forge refuses")
    class Rejected {

        private String messageOf(String name, String content) throws IOException {
            Path file = write(name, content);
            return assertThrows(SpecException.class, () -> SpecLoader.load(file)).getMessage();
        }

        @Test
        @DisplayName("no acceptance commands — nothing could ever verify it")
        void withoutAcceptanceCommands() throws IOException {
            String message = messageOf("bad.md", """
                    ---
                    id: "003"
                    title: Vague
                    ---
                    Make something nice.
                    """);
            assertTrue(message.contains("acceptance"), message);
        }

        @ParameterizedTest(name = "id {0}")
        @ValueSource(strings = {
                "../../word-processor", "..", "a/b", "a\\\\b", "007\\\" onmouseover", ".hidden", "x..y"})
        @DisplayName("an id that could leave the runs directory, or break a page, is refused")
        void unsafeIds(String id) throws IOException {
            // The id names the run directory an agent is handed with full permissions. Unchecked, an
            // id of ../../word-processor would have put a project inside a real repository.
            String message = messageOf("bad.md", """
                    ---
                    id: '%s'
                    title: Escape
                    acceptance:
                      - echo ok
                    ---
                    Build it.
                    """.formatted(id.replace("'", "''")));
            assertTrue(message.contains("'id' must be"), message);
        }

        @Test
        @DisplayName("a parent id is held to the same rule")
        void unsafeParent() throws IOException {
            String message = messageOf("bad.md", """
                    ---
                    id: "001.1"
                    title: Escape
                    parent: "../001"
                    acceptance:
                      - echo ok
                    ---
                    Build it.
                    """);
            assertTrue(message.contains("'parent' must be"), message);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "serve: npm run preview                          | must contain {port}",
                "deploy: { url: 'https://x.dev' }                 | needs a 'command'",
                "deploy: { command: go, url: 'http://x.dev' }     | https://",
                "monetization: { ads: always }                    | none, test or live",
                "monetization: { ads: live, provider: banners }   | adsense, admob or custom",
                "monetization: { ads: live, ids: { api_key: x } } | looks like a credential"})
        @DisplayName("outward settings that could not work, or should not be in a spec, are refused")
        void outwardSettingsAreChecked(String line, String expected) throws IOException {
            String message = messageOf("bad.md", """
                    ---
                    id: "009"
                    title: Outward
                    acceptance:
                      - echo ok
                    %s
                    ---
                    Build it.
                    """.formatted(line.strip()));
            assertTrue(message.contains(expected), message);
        }

        @Test
        void withoutFrontMatter() throws IOException {
            assertTrue(messageOf("bad.md", "Just some prose.\n").contains("front matter"));
        }

        @Test
        void withoutProse() throws IOException {
            String message = messageOf("bad.md", """
                    ---
                    id: "004"
                    title: Empty
                    acceptance:
                      - mvn -q test
                    ---
                    """);
            assertTrue(message.contains("prose"), message);
        }

        @Test
        void withUnparseableYaml() throws IOException {
            String message = messageOf("bad.md", """
                    ---
                    id: "005
                    title: [unclosed
                    ---
                    Build it.
                    """);
            assertTrue(message.contains("YAML"), message);
        }

        @Test
        @DisplayName("the message names the file, since nobody is watching when this fires")
        void namesTheOffendingFile() throws IOException {
            assertTrue(messageOf("broken-spec.md", "no front matter").contains("broken-spec.md"));
        }
    }

    @Test
    @DisplayName("loadAll ignores non-Markdown files and orders by filename")
    void loadsWishlistInOrder() throws IOException {
        write("002-b.md", VALID.replace("id: \"001\"", "id: \"002\""));
        write("001-a.md", VALID);
        write("notes.txt", "not a spec");

        assertEquals(List.of("001", "002"), SpecLoader.loadAll(dir).stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("one bad spec is skipped, not allowed to abort the whole queue")
    void oneInvalidSpecDoesNotStopTheOthers() throws IOException {
        write("001-good.md", VALID);
        write("README.md", "Notes about the wishlist, not a spec at all.\n");
        write("002-alsobad.md", "---\nid: \"002\"\ntitle: No acceptance\n---\nBuild it.\n");

        assertEquals(List.of("001"), SpecLoader.loadAll(dir).stream().map(Spec::id).toList());
    }

    @Test
    void missingWishlistDirectoryIsEmptyRatherThanFatal() {
        assertEquals(List.of(), SpecLoader.loadAll(dir.resolve("nope")));
    }
}
