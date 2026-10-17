package dev.fmangoni.forge.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.feature.Feature;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GateTest {

    @TempDir
    Path root;

    private Path repo;
    private Path evidence;
    private Gate gate;

    private static final String OVERVIEW = "Pipeline.java";

    /** What a project leaves for the person to double-click. */
    private static final String RUN_FILE = """
            @echo off
            cd /d "%~dp0"
            if not exist node_modules call npm ci || goto :failed
            start "" http://127.0.0.1:5173/
            call npm run dev
            exit /b 0
            :failed
            echo Could not install.
            pause
            """;

    /** Runs the gate with a valid overview, for the tests that are about something else. */
    private GateResult run(List<String> acceptance, List<String> evidence) {
        return gate.run(acceptance, evidence, OVERVIEW, List.of());
    }

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(root.resolve("repo"));
        Files.writeString(repo.resolve(OVERVIEW), """
                /** Reads the input, transforms it, writes the result. */
                public final class Pipeline {
                    public void run(Path in, Path out) {
                        var rows = reader.read(in);
                        writer.write(transformer.apply(rows), out);
                    }
                }
                """);
        Files.writeString(repo.resolve(RunFile.NAME), RUN_FILE);
        evidence = root.resolve("evidence");
        gate = new Gate(
                repo,
                root.resolve("scripts"),
                evidence,
                root.resolve("logs/gate.log"),
                Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("every command passing is the only way to pass")
    void passesWhenAllCommandsSucceed() {
        GateResult result = run(List.of("echo one", "echo two"), List.of());

        assertTrue(result.passed());
        assertEquals(2, result.commands().size());
    }

    @Test
    @DisplayName("a non-zero exit fails the gate — the whole point of the pipeline")
    void failsOnNonZeroExit() {
        GateResult result = run(List.of("exit 3"), List.of());

        assertFalse(result.passed());
        assertEquals(3, result.firstFailure().orElseThrow().exitCode());
    }

    @Test
    @DisplayName("commands stop at the first failure so the repair prompt names the real cause")
    void stopsAtFirstFailure() {
        GateResult result = run(List.of("echo first", "exit 1", "echo never"), List.of());

        assertFalse(result.passed());
        assertEquals(2, result.commands().size(), "the third command must not have run");
    }

    @Test
    @DisplayName("an empty acceptance list can never pass")
    void emptyAcceptanceCannotPass() {
        assertFalse(run(List.of(), List.of()).passed());
    }

    @Test
    void capturesOutputForTheRepairPrompt() {
        GateResult result = run(List.of("echo BUILD FAILURE marker && exit 1"), List.of());

        assertTrue(result.firstFailure().orElseThrow().output().contains("BUILD FAILURE marker"));
    }

    @Test
    @DisplayName("the failing command's script is kept so it can be rerun by hand")
    void keepsAReproducibleScript() {
        GateResult result = run(List.of("exit 1"), List.of());

        Path script = Path.of(result.firstFailure().orElseThrow().script());
        assertTrue(Files.isRegularFile(script), script + " should still exist");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("quotes in an acceptance command reach the tool unmangled")
    void preservesEmbeddedQuotes() throws IOException {
        run(List.of("echo -Dexec.args=\"a.pdf b.pdf\"> out.txt"), List.of());

        assertEquals("-Dexec.args=\"a.pdf b.pdf\"", Files.readString(repo.resolve("out.txt")).strip());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("acceptance runs as CI, so a test suite starts its own server rather than reusing one")
    void acceptanceRunsAsCi() throws IOException {
        // Pink Slip's playwright.config.ts: reuseExistingServer: !process.env.CI. Without CI set,
        // anything already on port 4173 — last session's preview build, another project's — was
        // what the gate tested and filmed.
        run(List.of("echo %CI%> ci.txt"), List.of());

        assertEquals("true", Files.readString(repo.resolve("ci.txt")).strip());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a server an acceptance command starts does not outlive the gate")
    void backgroundChildrenAreStopped() throws Exception {
        // Stands in for `npm run preview &`: a process that keeps writing after its command ended.
        // Left alive, it holds the port and file locks the next gate needs. Bounded at about forty
        // seconds, so a regression cannot leave it running for ever.
        Files.writeString(repo.resolve("beat.bat"), """
                @echo off
                for /l %%i in (1,1,40) do (
                  echo %%i>> beat.txt
                  ping -n 2 127.0.0.1 >nul
                )
                """);

        GateResult result = run(
                List.of("start \"\" /b cmd /c \"" + repo.resolve("beat.bat") + "\" >nul 2>&1 & ping -n 4 127.0.0.1 >nul"),
                List.of());
        assertTrue(result.passed(), result.summary());
        assertTrue(Files.exists(repo.resolve("beat.txt")), "the background process never ran");
        Thread.sleep(1500);
        long settled = Files.size(repo.resolve("beat.txt"));
        Thread.sleep(3500);

        assertEquals(settled, Files.size(repo.resolve("beat.txt")),
                "the background process was still writing after the gate returned");
    }

    @Test
    void collectsNominatedEvidence() throws IOException {
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/report.html"), "<h1>ok</h1>");
        Files.writeString(repo.resolve("ignored.txt"), "not evidence");

        GateResult result = run(List.of("echo build"), List.of("target/*.html"));

        assertEquals(1, result.evidence().size());
        assertTrue(Files.isRegularFile(evidence.resolve("target/report.html")));
    }

    @Test
    @DisplayName("passing tests but no nominated screenshot is not a pass")
    void missingEvidenceFailsTheGate() {
        GateResult result = run(List.of("echo built"), List.of("artifacts/gameplay.png"));

        assertFalse(result.passed(), "nothing a human could look at was produced");
        assertTrue(result.firstFailure().orElseThrow().output().contains("artifacts/gameplay.png"));
    }

    @Test
    @DisplayName("evidence that was produced passes")
    void producedEvidencePasses() throws IOException {
        Files.createDirectories(repo.resolve("artifacts"));
        Files.writeString(repo.resolve("artifacts/gameplay.png"), "not really a png");

        assertTrue(run(List.of("echo built"), List.of("artifacts/gameplay.png")).passed());
    }

    @Test
    @DisplayName("a failing build is not also blamed for producing no screenshots")
    void doesNotReportMissingEvidenceWhenTheBuildFailed() {
        GateResult result = run(List.of("exit 1"), List.of("artifacts/gameplay.png"));

        assertEquals(1, result.commands().size(), "the real failure should not be buried");
        assertEquals("exit 1", result.firstFailure().orElseThrow().command());
    }

    @Test
    @DisplayName("a spec naming no evidence is unaffected")
    void noEvidenceDeclaredMeansNoCheck() {
        assertTrue(run(List.of("echo built"), List.of()).passed());
    }

    // --------------------------------------------------------------- run file

    @Test
    @DisplayName("a project nobody can start by double-clicking something is not a pass")
    void noRunFileFailsTheGate() throws IOException {
        Files.delete(repo.resolve(RunFile.NAME));

        GateResult result = run(List.of("echo built"), List.of());

        assertFalse(result.passed(), "the person it was built for has no way to try it");
        CommandResult failure = result.firstFailure().orElseThrow();
        assertEquals("run file: run.bat", failure.command());
        assertTrue(failure.output().contains("no `run.bat` in the repository root"), failure.output());
        assertTrue(failure.output().contains("A way to run it"), "should say where the rule is");
    }

    @Test
    @DisplayName("a run file that only talks and pauses starts nothing, and is refused as that")
    void aRunFileThatStartsNothingIsRefused() throws IOException {
        Files.writeString(repo.resolve(RunFile.NAME), """
                @echo off
                rem TODO: start the app
                :: nothing yet
                cd /d "%~dp0"
                set PORT=5173
                echo See README.md for how to run this.
                pause
                """);

        GateResult result = run(List.of("echo built"), List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().output().contains("starts nothing"));
    }

    @Test
    @DisplayName("a run file saved in the Windows ANSI code page is read, not refused as unreadable")
    void aRunFileInTheAnsiCodePageIsRead() throws IOException {
        // What PowerShell's Set-Content writes. The em dash is the single byte 0x97, which is not
        // UTF-8, and reading the file as UTF-8 threw: the gate then said every line was a comment.
        Files.write(repo.resolve(RunFile.NAME), "@echo off\r\nrem Center Defense \u2014 starts the game\r\ncall npm run dev\r\n"
                .getBytes("windows-1252"));

        assertTrue(run(List.of("echo built"), List.of()).passed());
    }

    @Test
    @DisplayName("a byte-order mark in front of the first line does not hide what the file does")
    void aByteOrderMarkIsNotACommand() throws IOException {
        Files.write(repo.resolve(RunFile.NAME), ("\uFEFFecho hello\r\npause\r\n").getBytes(StandardCharsets.UTF_8));

        GateResult result = run(List.of("echo built"), List.of());

        assertFalse(result.passed(), "an echo and a pause start nothing, mark or no mark");
        assertTrue(result.firstFailure().orElseThrow().output().contains("starts nothing"));
    }

    @Test
    @DisplayName("the run file is read, never run: it would open a window and not come back")
    void theRunFileIsNotExecuted() throws IOException {
        Files.writeString(repo.resolve(RunFile.NAME), """
                @echo off
                copy nul started.txt
                call no-such-program.exe
                exit /b 7
                """);

        assertTrue(run(List.of("echo built"), List.of()).passed());
        assertFalse(Files.exists(repo.resolve("started.txt")), "the gate ran the run file");
    }

    @Test
    @DisplayName("a failing build is not also blamed for having no run file")
    void doesNotReportAMissingRunFileWhenTheBuildFailed() throws IOException {
        Files.delete(repo.resolve(RunFile.NAME));

        GateResult result = run(List.of("exit 1"), List.of());

        assertEquals(1, result.commands().size(), "the real failure should not be buried");
        assertEquals("exit 1", result.firstFailure().orElseThrow().command());
    }

    // ------------------------------------------------------------- recordings

    private Feature feature(String name) {
        return new Feature(name, "click the thing");
    }

    /**
     * An acceptance command that produces the recording, which is now the only way to have one.
     *
     * <p>The clip is kept outside the repository and copied in by the command, standing in for the
     * real arrangement: a Playwright run, or a Robot capture, wired into the suite the spec
     * already executes. A file simply written into the repository beforehand is deleted before the
     * commands run and never seen.
     */
    private String recordsDuringTheRun(Feature feature)
            throws IOException {
        Path source = root.resolve("capture/clip.gif");
        Clips.write(source);
        String target = feature.recordingPath(".webm");
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .startsWith("windows")
                ? "if not exist artifacts mkdir artifacts & copy /Y \"" + source + "\" \""
                        + target.replace('/', '\\') + "\" >nul"
                : "mkdir -p artifacts && cp \"" + source + "\" \"" + target + "\"";
    }

    /** A clip sitting in the repository already, as a committed artefact would be. */
    private void commit(Feature feature) throws IOException {
        Clips.write(repo.resolve(feature.recordingPath(".webm")));
    }

    @Test
    @DisplayName("an approved feature with nothing to watch is not a pass")
    void unfilmedFeatureFailsTheGate() {
        // Asking politely did not work. The house rules have requested recordings of interactive
        // work since the first line of them, and six projects produced one recording between them.
        GateResult result = gate.run(List.of("echo built"), List.of(), OVERVIEW,
                List.of(feature("Pink slip wager")));

        assertFalse(result.passed());
        String output = result.firstFailure().orElseThrow().output();
        assertTrue(output.contains("Pink slip wager"), output);
        assertTrue(output.contains("artifacts/feature-pink-slip-wager.webm"),
                "a requirement whose filename must be guessed is one that fails on spelling:\n"
                        + output);
        assertTrue(output.contains("click the thing"), "the agent is not told what to film:\n"
                + output);
    }

    @Test
    @DisplayName("a failed recordings check lists every approved clip, filmed or not")
    void everyOwedClipIsListed() throws IOException {
        // 003: one feature approved under two names, and one test filming it. Told only which name
        // was missing, the agent renamed the clip from the other each session until they ran out.
        var tuning = feature("Garage tuning");
        var customization = feature("Garage customization");

        GateResult result = gate.run(List.of(recordsDuringTheRun(tuning)), List.of(), OVERVIEW,
                List.of(customization, tuning));

        assertFalse(result.passed());
        String output = result.firstFailure().orElseThrow().output();
        assertTrue(output.contains("Garage tuning: filmed, at artifacts/feature-garage-tuning.webm"), output);
        assertTrue(output.contains("Garage customization: MISSING, expected at "
                + "artifacts/feature-garage-customization.webm"), output);
        assertTrue(output.contains("do not rename one clip to satisfy the other"), output);
    }

    @Test
    @DisplayName("a feature filmed by an acceptance command passes")
    void filmedDuringTheRunPasses() throws IOException {
        var filmed = feature("Pink slip wager");

        assertTrue(gate.run(List.of(recordsDuringTheRun(filmed)), List.of(), OVERVIEW,
                List.of(filmed)).passed());
    }

    @Test
    @DisplayName("a clip sitting in the repository is deleted before the commands run")
    void committedClipsDoNotCount() throws IOException {
        // The check that makes this mechanism worth anything. Forge cannot watch a video, so it
        // does not try to decide whether a clip is genuine — it requires the clip to be produced,
        // from source, by a command it ran itself. A hand-made file cannot survive that.
        var filmed = feature("Pink slip wager");
        commit(filmed);

        GateResult result = gate.run(List.of("echo built"), List.of(), OVERVIEW, List.of(filmed));

        assertFalse(result.passed(), "a committed clip was accepted as evidence of a working "
                + "feature, which is exactly what cannot be allowed");
        assertFalse(Files.exists(repo.resolve(filmed.recordingPath(".webm"))),
                "the stale clip is still there and would be shown as current");
    }

    @Test
    @DisplayName("a file that is not a recording is refused, and the reason is given")
    void stubsAreRefusedWithAReason() throws IOException {
        // An empty file with the right name satisfied the old check completely.
        var filmed = feature("Pink slip wager");
        String write = "echo not a video> "
                + filmed.recordingPath(".webm").replace('/', File.separatorChar);

        GateResult result = gate.run(
                List.of("if not exist artifacts mkdir artifacts", write), List.of(), OVERVIEW,
                List.of(filmed));

        assertFalse(result.passed());
        String output = result.firstFailure().orElseThrow().output();
        assertTrue(output.contains("stub") || output.contains("not a video file"), output);
    }

    @Test
    @DisplayName("the recordings are collected as evidence without the spec naming them")
    void recordingsAreCollectedAutomatically() throws IOException {
        // Owed by the contract rather than by the spec author, so requiring them in the evidence
        // list as well would only be a way to forget one.
        var filmed = feature("Garage tuning");

        gate.run(List.of(recordsDuringTheRun(filmed)), List.of(), OVERVIEW, List.of(filmed));

        assertTrue(Files.isRegularFile(
                evidence.resolve("artifacts/feature-garage-tuning.webm")), "not collected");
    }

    @Test
    @DisplayName("a partly filmed project still keeps the clips it did produce")
    void partialRecordingsAreStillCollected() throws IOException {
        // The report is how anyone finds out which of the two was filmed, and it can only show
        // what was collected. Withholding them until the set is complete hides the progress.
        var filmed = feature("Garage tuning");

        GateResult result = gate.run(List.of(recordsDuringTheRun(filmed)), List.of(), OVERVIEW,
                List.of(filmed, feature("Pink slip wager")));

        assertFalse(result.passed());
        assertTrue(Files.isRegularFile(evidence.resolve("artifacts/feature-garage-tuning.webm")));
    }

    @Test
    @DisplayName("a project with nothing approved owes nothing")
    void noApprovalsNoRecordings() {
        assertTrue(gate.run(List.of("echo built"), List.of(), OVERVIEW, List.of()).passed());
    }

    @Test
    @DisplayName("a failing build is not also blamed for having filmed nothing")
    void doesNotReportMissingRecordingsWhenTheBuildFailed() {
        GateResult result = gate.run(List.of("exit 1"), List.of(), OVERVIEW,
                List.of(feature("Pink slip wager")));

        assertEquals(1, result.commands().size(), "the real failure should not be buried");
        assertEquals("exit 1", result.firstFailure().orElseThrow().command());
    }

    @Test
    @DisplayName("no overview class named means the approach is undocumented, so it is not a pass")
    void missingOverviewFailsTheGate() {
        GateResult result = gate.run(List.of("echo built"), List.of(), "", List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().output().contains("overview"));
    }

    @Test
    @DisplayName("a turn that never handed off is told that, not that it forgot a field")
    void noHandoffIsReportedAsNoHandoff() {
        // Reported on a real project: a turn was killed mid-repair, Forge recovered and committed
        // the files, the acceptance commands all passed, and the gate then said "no overview class
        // was named in the handoff" — about a turn that never got as far as writing one. The next
        // repair brief would have sent the agent looking for a missing field in a document that
        // does not exist. Null and blank are different failures and read differently.
        GateResult result = gate.run(List.of("echo built"), List.of(), null, List.of());

        assertFalse(result.passed());
        String output = result.firstFailure().orElseThrow().output();
        assertTrue(output.contains("without reporting a handoff"), output);
        assertFalse(output.contains("No overview class was named"), output);
    }

    @Test
    @DisplayName("naming a file that was never written is caught")
    void overviewMustActuallyExist() {
        GateResult result = gate.run(List.of("echo built"), List.of(), "src/Pipeline.java", List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().output().contains("no such file"));
    }

    @Test
    @DisplayName("prose where the overview's path belongs fails the gate with a reason, not an exception")
    void proseIsNotAPath() {
        // Verbatim from project 004. The colon is illegal in a Windows path, resolving it threw,
        // and a gate whose every command had passed failed that way every twelve minutes for three
        // days. Elsewhere the same text is merely a file that does not exist; either way, a reason.
        GateResult result = gate.run(List.of("echo built"), List.of(),
                "not verified: workspace access failed before source inspection", List.of());

        assertFalse(result.passed());
        String output = result.firstFailure().orElseThrow().output();
        assertTrue(output.contains("not a path inside the repository") || output.contains("no such file"),
                output);
    }

    @Test
    @DisplayName("an overview outside the repository is not the repository's overview")
    void overviewMustBeInsideTheRepository() {
        GateResult result = gate.run(List.of("echo built"), List.of(), "../../house-rules.md", List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().output().contains("not a path inside"));
    }

    @Test
    @DisplayName("a stub named as the overview does not count")
    void overviewMustNotBeAStub() throws IOException {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Pipeline.java"), "// TODO");

        GateResult result = gate.run(List.of("echo built"), List.of(), "src/Pipeline.java", List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().output().contains("too small"));
    }

    @Test
    @DisplayName("a real overview class passes")
    void realOverviewPasses() throws IOException {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Pipeline.java"), """
                /** Reads the CSV, groups by category, renders the chart. */
                public final class Pipeline {
                    public void run(Path input, Path output) {
                        var rows = reader.read(input);
                        var totals = grouper.byCategory(rows);
                        renderer.toHtml(totals, output);
                    }
                }
                """);

        assertTrue(gate.run(List.of("echo built"), List.of(), "src/Pipeline.java", List.of()).passed());
    }

    @Test
    @DisplayName("an overview path escaping the repository is refused")
    void overviewCannotEscapeTheRepository() {
        GateResult result = gate.run(List.of("echo built"), List.of(), "../../../etc/passwd", List.of());

        assertFalse(result.passed());
    }

    @Test
    @DisplayName("a failing build is not also blamed for a missing overview")
    void doesNotReportMissingOverviewWhenTheBuildFailed() {
        GateResult result = gate.run(List.of("exit 1"), List.of(), null, List.of());

        assertEquals(1, result.commands().size());
        assertEquals("exit 1", result.firstFailure().orElseThrow().command());
    }

    @Test
    @DisplayName("a command that hangs is killed and counted as a failure")
    void killsAndFailsAHangingCommand() {
        Gate impatient = new Gate(
                repo, root.resolve("scripts"), evidence, root.resolve("logs/gate.log"),
                Duration.ofSeconds(3));

        GateResult result = impatient.run(List.of(hangCommand()), List.of(), OVERVIEW, List.of());

        assertFalse(result.passed());
        assertTrue(result.firstFailure().orElseThrow().timedOut());
    }

    private String hangCommand() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                ? "ping -n 60 127.0.0.1 > nul"
                : "sleep 60";
    }
}
