package dev.fmangoni.forge.pictures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.model.BatchRecord;
import dev.fmangoni.forge.pictures.model.JobRecord;
import dev.fmangoni.forge.pictures.model.JobState;
import dev.fmangoni.forge.pictures.model.Verdict;
import dev.fmangoni.forge.pictures.view.BatchView;
import dev.fmangoni.forge.pictures.view.JobView;
import dev.fmangoni.forge.pictures.work.PictureWatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A batch from its first look to its pictures placed, with real commands run as the watcher runs
 * them. Each card's check and placing are a few lines of batch file that print and exit as the
 * art tools do, so what is tested is what Forge does with what they say.
 */
class PictureFlowTest {

    private static final String ID = "042-pictures";

    private static final String MARKET = "repo/drafts/market.png";

    private static final String HARBOUR = "repo/drafts/harbour.png";

    private static final String BRIDGE = "repo/drafts/bridge.png";

    /**
     * The market's check passes with something to look at and writes a sheet; the harbour's finds a
     * problem; the bridge's passes, and placing the bridge fails.
     */
    private static final String CARDS = """
            # Lantern screens

            ## Job 1 of 3: Lantern Fair, Market

            Attach, in this order:

            1. `refs/style.png`

            Prompt:

            ```text
            Paint the market at dusk.
            ```

            Save the picture as `repo/drafts/market.png`, then run:

            ```bat
            echo LOOK: the sky is pale
            echo sheet> checks\\market-review.png
            exit /b 42
            ```

            Review sheet: `checks/market-review.png`

            Once kept, Forge places it with:

            ```bat
            echo placed market
            ```

            **STOP here.** Wait for the review.

            ## Job 2 of 3: Lantern Fair, Harbour

            Only after `Lantern Fair, Market` is kept: its picture is the first attachment.

            Attach, in this order:

            1. `repo/drafts/market.png`

            Prompt:

            ```text
            Paint the harbour in the market's light.
            ```

            Save the picture as `repo/drafts/harbour.png`, then run:

            ```bat
            echo PROBLEM: the boats are missing
            exit /b 1
            ```

            Review sheet: `checks/harbour-review.png`

            Once kept, Forge places it with:

            ```bat
            echo placed harbour
            ```

            ## Job 3 of 3: Lantern Fair, Bridge

            Prompt:

            ```text
            Paint the bridge.
            ```

            Save the picture as `repo/drafts/bridge.png`, then run:

            ```bat
            echo All good.
            ```

            Once kept, Forge places it with:

            ```bat
            echo cannot place the bridge: the project is busy
            exit /b 3
            ```
            """;

    @TempDir
    Path runs;

    private Path root;

    private PictureBatches batches;

    private PictureWatch watch;

    @BeforeEach
    void batch() throws IOException {
        root = Files.createDirectories(runs.resolve(ID));
        Files.createDirectories(root.resolve("refs"));
        Files.createDirectories(root.resolve("checks"));
        Files.writeString(root.resolve("refs/style.png"), "style");
        Files.writeString(root.resolve("rules.md"), "# What to check\n");
        Files.writeString(root.resolve("batch.json"), """
                {"title": "Lantern screens", "cards": "job-cards.md", "rules": "rules.md",
                 "anyway": "--anyway", "target": "042", "agent": "Astra"}
                """);
        Files.writeString(root.resolve("job-cards.md"), CARDS);
        batches = new PictureBatches(runs);
        watch = new PictureWatch(runs);
    }

    @Test
    @DisplayName("a new batch asks the agent for the first job alone, in the folder it works in, and says what waits")
    void firstLook() throws IOException {
        watch.pass();

        String list = read("repo/next-jobs.md");
        assertTrue(list.contains("Draw this job: attach the files in the order shown"), list);
        assertTrue(list.contains("Stop once it is saved."), list);
        assertTrue(list.contains("## Job 1 of 3: Lantern Fair, Market"), list);
        assertTrue(list.contains("1. `" + slashes(root.resolve("refs/style.png")) + "`"),
                "attachments are written out in full: " + list);
        assertTrue(list.contains("```text\nPaint the market at dusk.\n```"), list);
        assertTrue(list.contains("Save the picture as `" + slashes(root.resolve(MARKET)) + "`."), list);
        assertFalse(list.contains("Job 2"), "job 2 waits for job 1, and job 3 for the STOP: " + list);
        assertTrue(list.contains("2 more jobs wait until earlier pictures are kept."), list);
        assertTrue(Files.isDirectory(root.resolve("repo/drafts")), "the folder the picture goes in is ready");

        String prompt = read("repo/astra-prompt.md");
        String repo = slashes(root.resolve("repo"));
        assertTrue(prompt.contains("Start Astra in `" + repo + "`"), prompt);
        assertTrue(prompt.contains("Read " + repo + "/next-jobs.md and draw every job it lists, in order."), prompt);
        assertTrue(prompt.contains("everything you write goes inside " + repo + "."), prompt);

        BatchView view = view();
        assertTrue(prompt.contains(view.message()), "the page copies the same message the file holds");
        assertEquals(1, view.counts().get(JobState.TO_DRAW));
        assertEquals(2, view.counts().get(JobState.WAITING));
        assertEquals("/runs/" + ID + "/rules.md", view.rules());
        assertEquals(slashes(root.resolve("repo/astra-prompt.md")), view.promptFile());
        assertNull(view.problem());
    }

    @Test
    @DisplayName("a saved picture is checked once it has settled, and its review sheet, lines and log are kept")
    void checks() throws IOException {
        save(MARKET, "market one", Instant.now().plusSeconds(60));
        watch.pass();
        assertEquals(JobState.DRAWN, job(0).state(), "a picture written a moment ago may still be being saved");

        save(MARKET, "market one", ago(10));
        watch.pass();

        JobView market = job(0);
        assertEquals(JobState.CHECKED, market.state());
        assertEquals(Verdict.LOOK, market.check().verdict());
        assertEquals(42, market.check().exit());
        assertEquals(List.of("LOOK: the sky is pale"), market.check().lines());
        assertEquals("/runs/" + ID + "/checks/market-review.png?v=" + market.check().at().toEpochMilli(), market.sheet());
        assertEquals("/runs/" + ID + "/repo/drafts/market.png?v=" + market.stamp(), market.picture());
        assertTrue(market.check().log().startsWith("/runs/" + ID + "/logs/check-repo-drafts-market.log?v="),
                market.check().log());
        assertEquals(1, market.drawings());

        String list = read("repo/next-jobs.md");
        assertTrue(list.contains("Nothing to draw right now. Stop here."), list);
        assertTrue(list.contains("1 picture waits for a review."), list);

        watch.pass();
        assertEquals(1, record().find(MARKET).getChecks().size(), "a picture is checked once");
    }

    @Test
    @DisplayName("a ruling must be about the picture the person saw, once it is checked, and say what it needs to")
    void refusals() throws IOException {
        assertEquals(Optional.of("no picture batch 999-none"), batches.rule("999-none", MARKET, "1-1", true, null));
        assertEquals(Optional.of("no job saves a picture as repo/drafts/none.png"),
                batches.rule(ID, "repo/drafts/none.png", "1-1", true, null));
        assertEquals(Optional.of("the picture is gone"), batches.rule(ID, MARKET, "1-1", true, null));

        save(MARKET, "market one", ago(10));
        String stamp = job(0).stamp();
        assertEquals(Optional.of("it has not been checked yet"), batches.rule(ID, MARKET, stamp, true, null));

        watch.pass();
        assertEquals(Optional.of("say what to fix: it is added to the prompt"), batches.rule(ID, MARKET, stamp, false, "  "));
        assertEquals(Optional.of("it was drawn again since: review the new picture"),
                batches.rule(ID, MARKET, "1-1", true, null));
        assertEquals(Optional.empty(), batches.rule(ID, MARKET, stamp, true, null),
                "a picture that passed with something to look at is kept without a word");
        assertEquals(Optional.of("it was already reviewed"), batches.rule(ID, MARKET, stamp, false, "Brighter."));
    }

    @Test
    @DisplayName("drawing again adds the person's note to that job's prompt as one sentence, and the new picture is checked again")
    void redraw() throws IOException {
        save(MARKET, "market one", ago(20));
        watch.pass();
        assertEquals(Optional.empty(), batches.rule(ID, MARKET, job(0).stamp(), false, "  the lanterns\n should   glow "));

        JobView market = job(0);
        assertEquals(JobState.REDRAW, market.state());
        assertEquals("Paint the market at dusk. The lanterns should glow.", market.prompt());
        String list = read("repo/next-jobs.md");
        assertTrue(list.contains("Drawn again: the prompt ends with what to fix."), list);
        assertTrue(list.contains("```text\nPaint the market at dusk. The lanterns should glow.\n```"), list);

        save(MARKET, "market two!", ago(10));
        watch.pass();

        market = job(0);
        assertEquals(JobState.CHECKED, market.state());
        assertEquals(2, market.drawings());
        assertEquals("the lanterns\n should   glow", market.note(), "the review shows what was said");
        assertEquals("Paint the market at dusk.", market.prompt(), "the note was for the picture it was about");
    }

    @Test
    @DisplayName("keeping the first picture lets the next jobs through, and one click places it once")
    void keepAndPlace() throws IOException {
        save(MARKET, "market one", ago(10));
        watch.pass();
        assertEquals(Optional.empty(), batches.rule(ID, MARKET, job(0).stamp(), true, null));

        String list = read("repo/next-jobs.md");
        assertTrue(list.contains("Draw these 2 jobs, in order. For each, attach the files"), list);
        assertTrue(list.contains("Stop once these are saved."), list);
        assertTrue(list.contains("## Job 2 of 3: Lantern Fair, Harbour"), list);
        assertTrue(list.contains("## Job 3 of 3: Lantern Fair, Bridge"), list);
        assertTrue(list.contains("1. `" + slashes(root.resolve(MARKET)) + "`"), "the kept picture is attached to the next: " + list);
        assertEquals(1, view().placeable());

        assertEquals(Optional.of(1), batches.place(ID));
        assertTrue(job(0).placing());
        assertEquals(0, view().placeable(), "asked for once");
        assertEquals(Optional.of(0), batches.place(ID), "a second click before it runs asks for nothing more");

        watch.pass();

        JobView market = job(0);
        assertEquals(JobState.PLACED, market.state());
        assertFalse(market.placing());
        assertEquals(List.of("placed market"), record().find(MARKET).getPlaced().lines());

        Files.delete(root.resolve(MARKET));
        assertEquals(JobState.PLACED, job(0).state(), "placed stays placed when the agent's copy is cleared away");
        assertEquals(Optional.empty(), batches.place("999-none"));
    }

    @Test
    @DisplayName("a picture kept over a problem is placed with the batch's word for it; a failed placing is said until one works")
    void anywayAndFailure() throws IOException {
        save(MARKET, "market one", ago(30));
        watch.pass();
        assertEquals(Optional.empty(), batches.rule(ID, MARKET, job(0).stamp(), true, null));
        save(HARBOUR, "harbour one", ago(20));
        save(BRIDGE, "bridge one", ago(20));
        watch.pass();

        JobView harbour = job(1);
        assertEquals(Verdict.PROBLEM, harbour.check().verdict());
        assertNull(harbour.sheet(), "this check wrote no sheet");
        assertEquals(Optional.of("the check found a problem: say why it stands"),
                batches.rule(ID, HARBOUR, harbour.stamp(), true, ""));
        assertEquals(Optional.empty(), batches.rule(ID, HARBOUR, harbour.stamp(), true, "The boats are out at sea."));
        assertEquals(Optional.empty(), batches.rule(ID, BRIDGE, job(2).stamp(), true, null));

        assertEquals(Optional.of(3), batches.place(ID));
        watch.pass();

        assertEquals(List.of("placed market"), record().find(MARKET).getPlaced().lines(),
                "a picture that passed is placed as its card says");
        assertEquals(List.of("placed harbour --anyway"), record().find(HARBOUR).getPlaced().lines());
        JobView bridge = job(2);
        assertEquals(JobState.KEPT, bridge.state());
        assertEquals(Verdict.ERROR, bridge.placeFailed().verdict());
        assertEquals(3, bridge.placeFailed().exit());
        assertEquals(List.of("cannot place the bridge: the project is busy"), bridge.placeFailed().lines());
        assertEquals(1, view().placeable(), "it can be asked for again");
    }

    @Test
    @DisplayName("a picture drawn again after the click is not placed: the click is spent and the new one is reviewed")
    void drawnAgainAfterTheClick() throws IOException {
        save(MARKET, "market one", ago(30));
        watch.pass();
        assertEquals(Optional.empty(), batches.rule(ID, MARKET, job(0).stamp(), true, null));
        assertEquals(Optional.of(1), batches.place(ID));
        save(MARKET, "market two!", ago(10));

        watch.pass();

        JobRecord market = record().find(MARKET);
        assertNull(market.getPlaced());
        assertNull(market.getPlaceAsked());
        assertNull(market.getPlaceFailed());
        assertEquals(JobState.CHECKED, job(0).state());
    }

    @Test
    @DisplayName("a batch that cannot be read says why, the others carry on, and a project's run is passed by")
    void brokenBatches() throws IOException {
        Path missing = Files.createDirectories(runs.resolve("043-pictures"));
        Files.writeString(missing.resolve("batch.json"), "{\"cards\": \"missing.md\"}");
        Path outside = Files.createDirectories(runs.resolve("044-pictures"));
        Files.writeString(outside.resolve("batch.json"), "{}");
        Files.writeString(outside.resolve("job-cards.md"), "## Job 1 of 1: Ash Gate\n\nPrompt:\n\n```text\nPaint it.\n```\n\n"
                + "Save the picture as `../042-pictures/repo/drafts/market.png`, then run:\n\n```bat\necho ok\n```\n");
        Path project = Files.createDirectories(runs.resolve("041"));
        Files.writeString(project.resolve("state.json"), "{}");

        watch.pass();

        List<BatchView> all = batches.all();
        assertEquals(List.of(ID, "043-pictures", "044-pictures"), all.stream().map(BatchView::id).toList());
        assertTrue(all.get(1).problem().contains("missing.md"), all.get(1).problem());
        assertEquals("../042-pictures/repo/drafts/market.png is outside the batch folder", all.get(2).problem());
        assertTrue(Files.isRegularFile(root.resolve("repo/next-jobs.md")), "the batch that can be read is still looked after");
        assertFalse(Files.exists(outside.resolve("repo")), "nothing is written for a batch that cannot be read");
    }

    @Test
    @DisplayName("a list saved with a byte order mark, as Windows tools write one, reads the same")
    void byteOrderMark() throws IOException {
        Files.writeString(root.resolve("job-cards.md"), (char) 0xFEFF + CARDS);

        assertNull(view().problem());
        assertEquals(3, view().jobs().size());
    }

    private void save(String draft, String content, Instant modified) throws IOException {
        Path file = root.resolve(draft);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private static Instant ago(int seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    private BatchView view() {
        return batches.one(ID).orElseThrow();
    }

    private JobView job(int index) {
        return view().jobs().get(index);
    }

    private BatchRecord record() {
        return new RecordStore(root).read();
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
