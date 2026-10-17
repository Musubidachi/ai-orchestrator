package dev.fmangoni.forge.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asking the vendors for looks, and handing over the one that was chosen.
 *
 * <p>The designers are stubs that write pages into the folder they are given, which is the whole of
 * what a real one does as far as Forge can see. What is pinned is everything around the drawing:
 * that each vendor works beside the repository and not in it, that a vendor with no quota does not
 * stop the others, that a page which could not safely be shown is left off, and that the only thing
 * to reach the repository is the one file a person picked.
 */
class DesignStudioTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private Spec spec;
    /** Written from each vendor's thread: the vendors are asked at once. */
    private final List<AgentTask> tasks = Collections.synchronizedList(new ArrayList<>());

    private static final String PAGE = "<!doctype html><html><head><style>body{background:#101;color:#fff}"
            + "</style></head><body><h1>Center Defense</h1><svg viewBox=\"0 0 10 10\" xmlns=\"http://www.w3.org/2000/svg\">"
            + "<circle cx=\"5\" cy=\"5\" r=\"4\"/></svg><p>" + "The playfield, the avatar and the enemies. ".repeat(8)
            + "</p></body></html>";

    @BeforeEach
    void setUp() throws Exception {
        paths = new RunPaths(root.resolve("runs/004"));
        Files.createDirectories(paths.repo().resolve("src"));
        Files.writeString(paths.repo().resolve("index.html"), "<main id=\"playfield\"></main>");
        Files.writeString(paths.repo().resolve("src/game.js"), "export const start = () => {};");
        Files.createDirectories(paths.repo().resolve("node_modules/vite"));
        Files.writeString(paths.repo().resolve("node_modules/vite/index.js"), "// not ours");
        Files.createDirectories(paths.repo().resolve("artifacts"));
        Files.write(paths.repo().resolve("artifacts/gameplay.png"), new byte[] {1, 2, 3});
        Files.createDirectories(paths.repo().resolve("tests/fixtures"));
        Files.write(paths.repo().resolve("tests/fixtures/subject.jpg"), new byte[] {4, 5, 6});
        Files.createDirectories(paths.evidence().resolve("session-9"));
        Files.createDirectories(paths.evidence().resolve("session-10/artifacts"));
        Files.write(paths.evidence().resolve("session-9/old.png"), new byte[] {7});
        Files.write(paths.evidence().resolve("session-10/artifacts/game-over.png"), new byte[] {8});
        spec = SpecLoader.load(Files.writeString(root.resolve("004.md"), """
                ---
                id: "004"
                title: Center Defense
                acceptance:
                  - echo ok
                ---
                A game where you protect the centre.
                """));
    }

    /** A designer that writes the given pages and lists them. */
    private Agent designer(AgentKind kind, Map<String, String> pages, String index) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                tasks.add(task);
                try {
                    for (Map.Entry<String, String> page : pages.entrySet()) {
                        Files.writeString(task.cwd().resolve(page.getKey()), page.getValue());
                    }
                    if (index != null) {
                        Files.writeString(task.cwd().resolve("options.json"), index);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new AgentResult(AgentOutcome.COMPLETED, "done", new ProcResult(0, "", "", false, Duration.ZERO), null);
            }
        };
    }

    private static Agent outOfQuota(AgentKind kind) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                return new AgentResult(AgentOutcome.RATE_LIMITED, "limit", new ProcResult(1, "", "", false, Duration.ZERO), null);
            }
        };
    }

    private DesignBoard ask(Agent claude, Agent codex, String note) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, claude);
        map.put(AgentKind.CODEX, codex);
        return new DesignStudio(new Agents(map)).ask(spec, paths, note, "the avatar is a circle");
    }

    private static final String TWO = """
            [{"file":"option-1.html","name":"Neon arcade","pitch":"glowing outlines on black"},
             {"file":"option-2.html","name":"Paper cut","pitch":"flat shapes with soft shadows"}]
            """;

    @Test
    @DisplayName("every vendor is asked, each in a folder of its own beside the repository, and all their looks are offered")
    void everyVendorContributes() {
        DesignBoard board = ask(
                designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE), TWO),
                designer(AgentKind.CODEX, Map.of("option-1.html", PAGE),
                        "[{\"file\":\"option-1.html\",\"name\":\"Blueprint\",\"pitch\":\"thin white lines on blue\"}]"),
                "something bold");

        assertEquals(DesignBoard.READY, board.state());
        assertEquals(List.of("CLAUDE-1", "CLAUDE-2", "CODEX-1"), board.options().stream().map(DesignOption::id).toList());
        assertEquals(List.of("Neon arcade", "Paper cut", "Blueprint"),
                board.options().stream().map(DesignOption::name).toList());
        assertEquals("design/CLAUDE/option-2.html", board.options().get(1).file(),
                "relative to the run's folder, which is what the editor serves and the report links from");
        assertEquals("something bold", board.note());
        assertEquals(List.of(), board.refused());

        assertEquals(2, tasks.size());
        for (AgentTask task : tasks) {
            assertTrue(task.cwd().startsWith(paths.designDir()), "a designer was handed " + task.cwd());
            assertFalse(task.cwd().startsWith(paths.repo()), "a designer must never be handed the repository");
            assertFalse(task.readOnly(), "it has to write its pages");
            assertTrue(task.brief().contains("What they said they want: something bold"), task.brief());
            assertTrue(task.brief().contains("A game where you protect the centre."));
            assertTrue(task.brief().contains("the avatar is a circle"), "the builder's own account of it");
        }
        assertEquals(board, DesignBoard.load(paths.designBoardFile()), "what the page will read is what was returned");
    }

    @Test
    @DisplayName("a designer is given a copy of the project's source and screenshots to look at, and none of its dependencies")
    void designersGetACopyToReadFrom() {
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null), outOfQuota(AgentKind.CODEX), "");

        Path reference = paths.designDir().resolve("CLAUDE/reference");
        assertTrue(Files.isRegularFile(reference.resolve("source/index.html")));
        assertTrue(Files.isRegularFile(reference.resolve("source/src/game.js")));
        assertFalse(Files.exists(reference.resolve("source/node_modules")), "nobody designs from node_modules");
        assertTrue(Files.isRegularFile(reference.resolve("screens/gameplay.png")), "the project's own screenshot");
        assertTrue(Files.isRegularFile(reference.resolve("screens/game-over.png")), "and the newest gate's evidence");
        assertFalse(Files.exists(reference.resolve("screens/old.png")), "session 10 is newer than session 9");
        assertFalse(Files.exists(reference.resolve("screens/subject.jpg")),
                "a test's input picture is not a picture of how the program looks");
    }

    @Test
    @DisplayName("a vendor with no quota is named on the board, and the others still offer theirs")
    void aRefusalDoesNotStopTheRest() {
        DesignBoard board = ask(outOfQuota(AgentKind.CLAUDE),
                designer(AgentKind.CODEX, Map.of("option-1.html", PAGE, "option-2.html", PAGE), TWO), "");

        assertEquals(DesignBoard.READY, board.state());
        assertEquals(List.of("CODEX-1", "CODEX-2"), board.options().stream().map(DesignOption::id).toList());
        assertEquals(List.of("CLAUDE could not be asked: RATE_LIMITED"), board.refused());
    }

    @Test
    @DisplayName("nobody offering anything is said plainly, not shown as an empty choice")
    void nothingOffered() {
        DesignBoard board = ask(outOfQuota(AgentKind.CLAUDE), designer(AgentKind.CODEX, Map.of(), null), "");

        assertEquals(DesignBoard.NONE, board.state());
        assertEquals(List.of(), board.options());
        assertEquals(List.of("CLAUDE could not be asked: RATE_LIMITED", "CODEX offered nothing"), board.refused());
    }

    @Test
    @DisplayName("a page that reaches for the network, is not there, or is not an option's name is left off, with the reason")
    void unusableOptionsAreDropped() {
        String fetching = PAGE.replace("<h1>", "<link rel=\"stylesheet\" href=\"https://fonts.example/css\"><h1>");
        DesignBoard board = ask(
                designer(AgentKind.CLAUDE, Map.of("option-1.html", fetching, "option-3.html", PAGE), """
                        [{"file":"option-1.html","name":"Fetches a font","pitch":""},
                         {"file":"option-2.html","name":"Never written","pitch":""},
                         {"file":"../repo/index.html","name":"Somewhere else","pitch":""},
                         {"file":"option-3.html","name":"Fine","pitch":"the one that can be shown"}]
                        """),
                outOfQuota(AgentKind.CODEX), "");

        assertEquals(List.of("Fine"), board.options().stream().map(DesignOption::name).toList());
        assertEquals("CLAUDE-1", board.options().getFirst().id(), "numbered by what is shown, not by what was offered");
        assertTrue(board.refused().contains("CLAUDE option-1.html: loads something from the network"), board.refused().toString());
        assertTrue(board.refused().contains("CLAUDE option-2.html: listed but never written"), board.refused().toString());
        assertTrue(board.refused().contains("CLAUDE ../repo/index.html: not a file name an option may have"),
                board.refused().toString());
    }

    @Test
    @DisplayName("an inline SVG's namespace is not the network, and pages written without a list are still found")
    void lenientWhereItIsSafe() {
        DesignBoard board = ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE), null),
                outOfQuota(AgentKind.CODEX), "");

        assertEquals(List.of("Option 1", "Option 2"), board.options().stream().map(DesignOption::name).toList());
    }

    @Test
    @DisplayName("no vendor offers more than its share, however many pages it wrote")
    void cappedPerVendor() {
        DesignBoard board = ask(designer(AgentKind.CLAUDE,
                        Map.of("option-1.html", PAGE, "option-2.html", PAGE, "option-3.html", PAGE, "option-4.html", PAGE), null),
                outOfQuota(AgentKind.CODEX), "");

        assertEquals(DesignStudio.PER_VENDOR, board.options().size());
    }

    @Test
    @DisplayName("asking again starts from nothing: last time's pages are not this time's answer")
    void askingAgainClearsTheBoard() {
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE), TWO),
                outOfQuota(AgentKind.CODEX), "");

        DesignBoard second = ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null),
                outOfQuota(AgentKind.CODEX), "");

        assertEquals(1, second.options().size());
        assertFalse(Files.exists(paths.designDir().resolve("CLAUDE/option-2.html")));
    }

    @Test
    @DisplayName("asking for more like one design keeps it first on the new board, and every designer is handed it and told to keep it")
    void moreLikeOneDesign() throws IOException {
        // 003: "I like the Sticker Garage, try different car looks, the rest looks fine" reached
        // designers who could not see that page. They drew new screens, and the page that was liked
        // was deleted with the rest of the board.
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE.replace("#101", "#fde")), TWO),
                outOfQuota(AgentKind.CODEX), "");
        tasks.clear();

        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null));
        map.put(AgentKind.CODEX, outOfQuota(AgentKind.CODEX));
        DesignBoard board = new DesignStudio(new Agents(map)).ask(spec, paths, "different cars", "", "CLAUDE-2");

        assertEquals(List.of(DesignBoard.LIKED, "CLAUDE-1"), board.options().stream().map(DesignOption::id).toList());
        DesignOption liked = board.options().get(0);
        assertEquals("Paper cut", liked.name());
        assertEquals("design/liked.html", liked.file());
        assertTrue(Files.readString(paths.root().resolve(liked.file())).contains("#fde"),
                "the page that was liked, not the new one drawn into its old slot");
        assertEquals(DesignBoard.READY, board.state());

        assertTrue(Files.readString(paths.designDir().resolve("CLAUDE/reference/liked.html")).contains("#fde"));
        String brief = tasks.get(0).brief();
        assertTrue(brief.contains("\"Paper cut\" — flat shapes with soft shadows"), brief);
        assertTrue(brief.contains("It is `reference/liked.html` in this folder"), brief);
        assertTrue(brief.contains("Change only what they asked to change:\n\ndifferent cars"), brief);
        assertFalse(brief.contains("What they said they want"), "not the wording for starting from nothing");

        assertTrue(DesignStudio.choose(paths, DesignBoard.LIKED, "").isPresent(), "it can still be the one built");
    }

    @Test
    @DisplayName("extras are drawn beside the current look, the board says what kind they are, and animations may move")
    void extrasAreDrawnBesideTheLook() {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null));
        map.put(AgentKind.CODEX, outOfQuota(AgentKind.CODEX));

        DesignBoard theme = new DesignStudio(new Agents(map)).ask(spec, paths, "Halloween", "", "", DesignBoard.THEME);

        assertEquals(DesignBoard.THEME, theme.extra());
        String brief = tasks.get(0).brief();
        assertTrue(brief.startsWith("Draw 2 themes for the program described below"), brief);
        assertTrue(brief.contains("added beside the look it has now, which stays"), brief);
        assertTrue(brief.contains("What they said they want: Halloween"), brief);
        assertTrue(brief.contains("A still picture of the look"), "a theme is chosen from a still");

        tasks.clear();
        DesignBoard motion = new DesignStudio(new Agents(map)).ask(spec, paths, "a burnout", "", "", DesignBoard.ANIMATION);
        assertEquals(DesignBoard.ANIMATION, motion.extra());
        String animated = tasks.get(0).brief();
        assertTrue(animated.contains("CSS `@keyframes`"), animated);
        assertFalse(animated.contains("A still picture of the look"), "nobody can choose an animation from a still");
        assertTrue(animated.contains("JavaScript still is not"), animated);

        DesignBoard plain = ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null),
                outOfQuota(AgentKind.CODEX), "");
        assertEquals("", plain.extra(), "looks to replace the current one are not extras");
    }

    @Test
    @DisplayName("choosing one marks it and puts that one page in the repository, where the builder can open it")
    void choosingHandsOverOnePage() throws IOException {
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE.replace("#101", "#fed")), TWO),
                outOfQuota(AgentKind.CODEX), "");

        DesignOption picked = DesignStudio.choose(paths, "CLAUDE-2", "").orElseThrow();

        assertEquals("Paper cut", picked.name());
        String inRepo = Files.readString(paths.repo().resolve(DesignStudio.CHOSEN_IN_REPO));
        assertTrue(inRepo.contains("#fed"), "the page that was picked, not the first one");
        DesignBoard board = DesignBoard.load(paths.designBoardFile());
        assertEquals("CLAUDE-2", board.chosen());
        assertEquals("Paper cut", board.chosenOption().orElseThrow().name());
        assertTrue(board.chosenAt() != null);
    }

    @Test
    @DisplayName("the vendors are asked at once, not one after another")
    void vendorsDrawInParallel() {
        // Each waits until the other has started. One after another, the first would wait for ever.
        CountDownLatch bothStarted = new CountDownLatch(2);
        Agent claude = waiting(AgentKind.CLAUDE, bothStarted);
        Agent codex = waiting(AgentKind.CODEX, bothStarted);

        DesignBoard board = ask(claude, codex, "");

        assertEquals(List.of("CLAUDE-1", "CODEX-1"), board.options().stream().map(DesignOption::id).toList(),
                "in vendor order, whichever answered first");
    }

    /** A designer that draws one page, but only once {@code together} says every designer has started. */
    private Agent waiting(AgentKind kind, CountDownLatch together) {
        Agent drawing = designer(kind, Map.of("option-1.html", PAGE), null);
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                together.countDown();
                try {
                    if (!together.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(kind + " was asked alone: the vendors were not asked at once");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return drawing.run(task);
            }
        };
    }

    @Test
    @DisplayName("a vendor the quota ledger knows to be closed is not called, and the board says until when")
    void aClosedVendorIsNotAsked() {
        QuotaLedger quota = new QuotaLedger(root.resolve("quota.json"));
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "limit");
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null));
        map.put(AgentKind.CODEX, designer(AgentKind.CODEX, Map.of("option-1.html", PAGE), null));

        DesignBoard board = new DesignStudio(new Agents(map, null, quota)).ask(spec, paths, "", "");

        assertEquals(List.of("CODEX-1"), board.options().stream().map(DesignOption::id).toList());
        assertEquals(1, tasks.size(), "only the open vendor was called");
        assertTrue(board.refused().getFirst().startsWith("CLAUDE is out of quota until "), board.refused().toString());
        assertTrue(board.refused().getFirst().endsWith(", so it was not asked"));
    }

    @Test
    @DisplayName("stopped mid-request, the board says it was cut short and keeps what had arrived")
    void anInterruptedRequestSaysSo() throws Exception {
        CountDownLatch codexStarted = new CountDownLatch(1);
        Agent slow = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                codexStarted.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgentResult(AgentOutcome.FAILED, "killed", new ProcResult(-1, "", "", false, Duration.ZERO), null);
            }
        };
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null));
        map.put(AgentKind.CODEX, slow);
        DesignStudio studio = new DesignStudio(new Agents(map));
        DesignBoard[] answer = new DesignBoard[1];
        Thread job = Thread.ofPlatform().start(() -> answer[0] = studio.ask(spec, paths, "", ""));

        assertTrue(codexStarted.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 200 && DesignBoard.load(paths.designBoardFile()).options().isEmpty(); i++) {
            Thread.sleep(25);
        }
        job.interrupt();
        job.join(20_000);

        assertEquals(DesignBoard.INTERRUPTED, answer[0].state());
        assertEquals(DesignBoard.INTERRUPTED, DesignBoard.load(paths.designBoardFile()).state(),
                "and on disk, where the page reads it");
        assertEquals(List.of("CLAUDE-1"), answer[0].options().stream().map(DesignOption::id).toList());
        assertTrue(answer[0].refused().contains("stopped before every model had answered"), answer[0].refused().toString());
    }

    @Test
    @DisplayName("the design that was built is kept for the report when designs are asked for again")
    void theChoiceOutlivesTheBoard() {
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE, "option-2.html", PAGE), TWO),
                outOfQuota(AgentKind.CODEX), "");
        DesignStudio.choose(paths, "CLAUDE-2", "");

        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null), outOfQuota(AgentKind.CODEX), "");

        assertEquals("", DesignBoard.load(paths.designBoardFile()).chosen(), "a new board, nothing chosen on it");
        PolishRecord record = PolishRecord.load(paths.polishFile());
        assertEquals("Paper cut", record.option().name(), "but what was built is still known");
        assertTrue(record.chosenAt() != null);
    }

    @Test
    @DisplayName("srcset and a meta refresh are caught as reaching for the network too")
    void theQuieterWaysToTheNetwork() {
        String srcset = PAGE.replace("<h1>", "<img srcset=\"local.png 1x, https://tracker.example/p.png 2x\"><h1>");
        String refresh = PAGE.replace("<head>", "<head><meta http-equiv=\"refresh\" content=\"0;url=https://tracker.example/\">");
        DesignBoard board = ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", srcset, "option-2.html", refresh), TWO),
                outOfQuota(AgentKind.CODEX), "");

        assertEquals(List.of(), board.options());
        assertTrue(board.refused().contains("CLAUDE option-1.html: loads something from the network"), board.refused().toString());
        assertTrue(board.refused().contains("CLAUDE option-2.html: loads something from the network"), board.refused().toString());
    }

    @Test
    @DisplayName("an option that is not on the board changes nothing")
    void choosingSomethingThatIsNotThere() {
        ask(designer(AgentKind.CLAUDE, Map.of("option-1.html", PAGE), null), outOfQuota(AgentKind.CODEX), "");

        assertTrue(DesignStudio.choose(paths, "CODEX-9", "").isEmpty());
        assertFalse(Files.exists(paths.repo().resolve(DesignStudio.CHOSEN_IN_REPO)));
        assertEquals("", DesignBoard.load(paths.designBoardFile()).chosen());
    }
}
