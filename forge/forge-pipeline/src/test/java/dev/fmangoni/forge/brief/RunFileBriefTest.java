package dev.fmangoni.forge.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.RunFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunFileBriefTest {

    @TempDir
    Path repo;

    @Test
    @DisplayName("a project with no run file is told to add one, and where the rule is")
    void owedWhileMissing() {
        String note = RunFileBrief.ifOwed(repo);

        assertTrue(note.contains("Also owed in this turn: a working `run.bat`"), note);
        assertTrue(note.contains("There is no `run.bat` in the repository root."), note);
        assertTrue(note.contains("A way to run it"), "should name the section of the rules");
        assertTrue(note.startsWith("\n\n"), "it is appended to a brief, and must not run into it");
    }

    @Test
    @DisplayName("a run file the gate would refuse does not silence the note, which says why")
    void aStubStillOwesOne() throws IOException {
        Files.writeString(repo.resolve(RunFile.NAME), "@echo off\necho See README.md\npause\n");

        String note = RunFileBrief.ifOwed(repo);

        assertTrue(note.contains("exists but starts nothing"), note);
    }

    @Test
    @DisplayName("once the file is there, the note is gone and the brief is exactly what it was")
    void nothingOnceItExists() throws IOException {
        Files.writeString(repo.resolve(RunFile.NAME), "@echo off\ncall npm run dev\n");

        assertEquals("", RunFileBrief.ifOwed(repo));
    }
}
