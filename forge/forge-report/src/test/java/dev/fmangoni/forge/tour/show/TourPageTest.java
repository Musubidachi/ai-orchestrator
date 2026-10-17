package dev.fmangoni.forge.tour.show;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.tour.model.Tour;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The page a tour plays itself in.
 *
 * <p>The thing that can actually break here is the code getting into the page: a tour is made of
 * source, source contains every character HTML and JavaScript treat as special, and the one
 * sequence that can end a script block early — {@code </script>} — appears in any codebase with a
 * web page in it. This one has it on purpose.
 */
class TourPageTest {

    private Tour tour() {
        CodeView view = new CodeView("Page.java", 10,
                List.of("String close = \"</script>\";", "return close;"), 10, 10);
        return new Tour("Demo", "one class", List.of(
                new Chapter("Page", "demo · Page.java", List.of(
                        new Scene("Page", "Takes close from the words end of a script block.", view),
                        Scene.spoken("In summary", "That was 1 class in 1 module.")))));
    }

    @Test
    @DisplayName("code containing a closing script tag cannot end the page's own script block")
    void theOneSequenceThatMatters() {
        String page = TourPage.render(tour());
        assertTrue(page.contains("<\\/script>"), "the tag was not escaped on its way in");
        // Two blocks on the page: the data and the player. A third closing tag means the code
        // ended one of them early, which is a broken page and, on a real site, worse.
        assertEquals(2, page.split("</script>", -1).length - 1,
                "the code closed a script block that was not its own");
    }

    @Test
    @DisplayName("the page carries the tour with it, so it plays with nothing else present")
    void selfContained() {
        String page = TourPage.render(tour());
        assertTrue(page.contains("Takes close from the words end of a script block."));
        assertTrue(page.contains("speechSynthesis"), "no voice on the page");
        assertFalse(page.contains("http://"), "the page reaches for something it will not have");
        assertFalse(page.contains("https://"), "the page reaches for something it will not have");
    }

    @Test
    @DisplayName("written as one file, opened by double-clicking it")
    void written(@TempDir Path out) throws IOException {
        Path file = out.resolve("deep").resolve("tour.html");
        TourPage.write(tour(), file);
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith("<!doctype html>"));
    }
}
