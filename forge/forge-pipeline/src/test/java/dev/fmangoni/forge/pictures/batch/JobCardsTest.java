package dev.fmangoni.forge.pictures.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.pictures.model.PictureJob;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobCardsTest {

    /** Two cards as the event tools write them, after a preamble that is said to the person only. */
    private static final String LIST = """
            # Event screens

            Read this first. Each job is one picture.

            ```bat
            echo this is not a job
            ```

            ## Job 1 of 3: Lantern Fair, Market

            Attach, in this order:

            1. `refs/style.png`
            2. `locked/lantern/market-base.png`

            Prompt:

            ```text
            Paint the market square at dusk.
            Keep the stalls where they are.
            ```

            Save the picture as `repo/drafts/lantern/market.png`, then run:

            ```bat
            python tools/screens.py check lantern market
            ```

            Review sheet: `checks/lantern-market-review.png`

            Once kept, Forge places it with:

            ```bat
            python tools/screens.py accept lantern market
            ```

            **STOP here.** Wait for the review before Job 2.

            ## Job 2 of 3: Lantern Fair, Harbour

            Only after `Lantern Fair, Market` is kept: its picture is the first attachment.

            Attach, in this order:

            1. `repo/drafts/lantern/market.png`

            Prompt:

            ```text
            The harbour, in the market's light.
            ```

            Notes:

            1. `not/an/attachment.png`

            Save the picture as `repo/drafts/lantern/harbour.png`, then run:

            ```bat
            python tools/screens.py check lantern harbour
            ```
            """;

    @Test
    @DisplayName("a card gives its attachments in order, its prompt as written, and its three commands' parts")
    void readsAnEventCard() {
        List<PictureJob> jobs = JobCards.parse(LIST);

        assertEquals(2, jobs.size(), "the preamble and its block are not a job");
        PictureJob market = jobs.get(0);
        assertEquals(1, market.number());
        assertEquals(3, market.total());
        assertEquals("Lantern Fair, Market", market.title());
        assertNull(market.after());
        assertEquals(List.of("refs/style.png", "locked/lantern/market-base.png"), market.attach());
        assertEquals("Paint the market square at dusk.\nKeep the stalls where they are.", market.prompt(),
                "the prompt is pasted exactly, so its lines are kept");
        assertEquals("repo/drafts/lantern/market.png", market.draft());
        assertEquals("python tools/screens.py check lantern market", market.check());
        assertEquals("checks/lantern-market-review.png", market.sheet());
        assertEquals("python tools/screens.py accept lantern market", market.place(),
                "the block after the review sheet places the picture, whatever is said between them");
    }

    @Test
    @DisplayName("a STOP line holds the jobs after it, and only the card it is on carries it")
    void stopLine() {
        List<PictureJob> jobs = JobCards.parse(LIST);

        assertTrue(jobs.get(0).stop());
        assertFalse(jobs.get(1).stop());
    }

    @Test
    @DisplayName("a card that waits names the job it waits for, and a numbered path outside the attach list is not attached")
    void onlyAfter() {
        PictureJob harbour = JobCards.parse(LIST).get(1);

        assertEquals("Lantern Fair, Market", harbour.after());
        assertEquals(List.of("repo/drafts/lantern/market.png"), harbour.attach());
        assertNull(harbour.sheet(), "this card has no review sheet");
        assertNull(harbour.place(), "and nothing places it: the masters' cards are like this");
    }

    @Test
    @DisplayName("a card with no review sheet can still say how a kept picture is placed")
    void placeWithoutSheet() {
        PictureJob job = JobCards.parse("## Job 1 of 1: Ash Gate\n\nPrompt:\n\n```text\nPaint it.\n```\n\n"
                + "Save the picture as `repo/a.png`, then run:\n\n```bat\necho checked\n```\n\n"
                + "Once kept, Forge places it with:\n\n```bat\necho placed\n```\n").get(0);

        assertNull(job.sheet());
        assertEquals("echo checked", job.check());
        assertEquals("echo placed", job.place());
    }

    @Test
    @DisplayName("a list with no job headings has no jobs")
    void noJobs() {
        assertEquals(List.of(), JobCards.parse("# Nothing yet\n\nThe cards are being written.\n"));
    }

    @Test
    @DisplayName("a card missing its prompt, its picture or its check is refused, naming the job and what is missing")
    void incompleteCards() {
        String check = "Save the picture as `repo/a.png`, then run:\n\n```bat\necho ok\n```\n";
        String prompt = "Prompt:\n\n```text\nPaint it.\n```\n\n";

        IllegalArgumentException noPrompt = assertThrows(IllegalArgumentException.class,
                () -> JobCards.parse("## Job 4 of 9: Ash Gate\n\n" + check));
        IllegalArgumentException noPicture = assertThrows(IllegalArgumentException.class,
                () -> JobCards.parse("## Job 4 of 9: Ash Gate\n\n" + prompt + "```bat\necho ok\n```\n"));
        IllegalArgumentException noCheck = assertThrows(IllegalArgumentException.class,
                () -> JobCards.parse("## Job 4 of 9: Ash Gate\n\n" + prompt + "Save the picture as `repo/a.png`.\n"));

        assertEquals("Job 4 (Ash Gate) has no prompt", noPrompt.getMessage());
        assertEquals("Job 4 (Ash Gate) has no picture to save", noPicture.getMessage());
        assertEquals("Job 4 (Ash Gate) has no check command", noCheck.getMessage());
    }
}
