package dev.fmangoni.forge.ui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecApiTest {

    @TempDir
    Path wishlist;

    private SpecApi api;

    @BeforeEach
    void setUp() {
        api = new SpecApi(wishlist);
        api.save(draft("003", "Invoice batch importer", ""));
    }

    private static Draft draft(String id, String title, String originalFile) {
        Draft draft = new Draft();
        draft.getFront().setId(id);
        draft.getFront().setTitle(title);
        draft.getFront().setAcceptance(List.of("npm test"));
        draft.setBody("Build it.");
        draft.setOriginalFile(originalFile);
        return draft;
    }

    @Test
    @DisplayName("a new spec cannot take an id another spec already has")
    void newSpecWithATakenIdIsRefused() throws Exception {
        SaveResult result = api.save(draft("003", "Timesheet report", ""));

        assertFalse(result.valid());
        assertTrue(result.error().contains("003-invoice-batch-importer.md"), result.error());
        try (var files = Files.list(wishlist)) {
            assertEquals(1, files.count(), "nothing was written");
        }
    }

    @Test
    @DisplayName("a new spec matching an existing one's id and title does not overwrite it")
    void sameFilenameIsNotOverwritten() throws Exception {
        Path existing = wishlist.resolve("003-invoice-batch-importer.md");
        String before = Files.readString(existing);

        Draft copy = draft("003", "Invoice batch importer", "");
        copy.setBody("Something else entirely.");

        assertFalse(api.save(copy).valid());
        assertEquals(before, Files.readString(existing));
    }

    @Test
    @DisplayName("saving a spec over itself, or renaming it, is still allowed")
    void editingAndRenamingStillWork() {
        assertTrue(api.save(draft("003", "Invoice batch importer", "003-invoice-batch-importer.md"))
                .valid());

        SaveResult renamed = api.save(
                draft("003", "Invoices", "003-invoice-batch-importer.md"));

        assertTrue(renamed.valid(), renamed.error());
        assertEquals(List.of("003-invoices.md"),
                api.list().stream().map(SpecSummary::file).toList());
    }

    /** A run left behind by a spec that has since been deleted from the wishlist. */
    private SpecApi withEarlierRun(String id, String title) {
        Path runs = wishlist.resolve("runs");
        RunStore.save(
                RunPaths.under(runs, id).stateFile(),
                RunState.builder().id(id).title(title)
                        .phase(Phase.DONE).build());
        return new SpecApi(wishlist, runs);
    }

    @Test
    @DisplayName("a new spec cannot inherit the run a deleted spec left behind")
    void aDeletedSpecsRunIsNotInherited() {
        SpecApi withRuns = withEarlierRun("007", "Crochet pattern differ");

        SaveResult result = withRuns.save(draft("007", "Expense tracker", ""));

        assertFalse(result.valid());
        assertTrue(result.error().contains("Crochet pattern differ"), result.error());
    }

    @Test
    @DisplayName("bringing a deleted spec back, under its old title, is allowed")
    void aDeletedSpecCanBeRestored() {
        SpecApi withRuns = withEarlierRun("007", "Crochet pattern differ");

        assertTrue(withRuns.save(draft("007", "crochet pattern differ", "")).valid());
        assertTrue(withRuns.save(draft("003", "Invoice batch importer",
                "003-invoice-batch-importer.md")).valid(), "a spec that exists edits as before");
    }

    @Test
    @DisplayName("a suggested id is kept when free, and replaced by the next number when not")
    void suggestedIdsAreMadeUsable() {
        SpecApi withRuns = withEarlierRun("007", "Crochet pattern differ");

        assertEquals("012", withRuns.usableId("012"), "a free id is the model's to choose");
        assertEquals("008", withRuns.usableId("003"), "taken by a spec in the wishlist");
        assertEquals("008", withRuns.usableId("007"), "taken by a run a deleted spec left");
        assertEquals("008", withRuns.usableId(""));
        assertEquals("008", withRuns.usableId("../../escape"));
    }

    @Test
    @DisplayName("changing a spec's id to one already taken is refused")
    void reassigningToATakenIdIsRefused() {
        api.save(draft("004", "Timesheet report", ""));

        assertFalse(api.save(draft("003", "Timesheet report", "004-timesheet-report.md")).valid());
        assertEquals(2, api.list().size());
    }
}
