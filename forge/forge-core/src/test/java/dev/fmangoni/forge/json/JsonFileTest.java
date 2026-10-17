package dev.fmangoni.forge.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.project.DashboardSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a configuration file the way a Windows editor left it.
 *
 * <p>Every one of these files exists to be hand-edited, and the editors people use on Windows write
 * a UTF-8 byte-order mark. Jackson refuses it — {@code Unexpected character ('?' (code 65279))} —
 * so a file that looks right in Notepad is silently ignored and the setting appears not to work.
 *
 * <p>Not hypothetical. Writing {@code dashboard.json} from PowerShell with
 * {@code Out-File -Encoding utf8} produced exactly that, and the queue stayed off with only a line
 * on stderr to say why.
 */
class JsonFileTest {

    @TempDir
    Path root;

    /** What PowerShell's {@code Out-File -Encoding utf8} and Notepad both produce. */
    private Path withBom(String name, String json) throws IOException {
        Path file = root.resolve(name);
        Files.writeString(file, "﻿" + json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a byte-order mark is stripped rather than parsed")
    void stripsTheMark() throws IOException {
        Path file = withBom("thing.json", "{\"autoRun\": true}");

        assertEquals("{\"autoRun\": true}", JsonFile.read(file));
    }

    @Test
    @DisplayName("a file without one is returned untouched")
    void leavesCleanFilesAlone() throws IOException {
        Path file = root.resolve("clean.json");
        Files.writeString(file, "{\"autoRun\": false}", StandardCharsets.UTF_8);

        assertEquals("{\"autoRun\": false}", JsonFile.read(file));
    }

    @Test
    @DisplayName("an empty file is empty, not an index error")
    void toleratesEmpty() throws IOException {
        Path file = root.resolve("empty.json");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        assertEquals("", JsonFile.read(file));
    }

    @Test
    @DisplayName("the dashboard setting loads from a file written with a mark")
    void dashboardSettingsSurviveABom() throws IOException {
        withBom("dashboard.json", "{\"autoRun\": true}");

        // The failure this prevents: the queue silently staying off after someone switched it on.
        assertTrue(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("the vendor policy loads from a file written with a mark")
    void vendorPolicySurvivesABom() throws IOException {
        withBom("vendors.json",
                "{\"CLAUDE\": {\"builds\": true, \"concurrency\": 2},"
                        + " \"CODEX\": {\"builds\": false}}");

        VendorPolicy policy =
                VendorPolicy.load(root);

        assertEquals(2, policy.concurrency(AgentKind.CLAUDE));
        assertTrue(!policy.buildsNewProjects(AgentKind.CODEX));
    }

    // ------------------------------------------------------------- writing

    @Test
    @DisplayName("a write replaces the file whole and leaves nothing beside it")
    void writeReplacesAtomically() throws IOException {
        Path file = root.resolve("runs/001/decisions.json");

        JsonFile.write(file, "[{\"note\":\"first\"}]");
        JsonFile.write(file, "[{\"note\":\"second\"}]");

        assertEquals("[{\"note\":\"second\"}]", Files.readString(file));
        assertTrue(Files.notExists(file.resolveSibling("decisions.json.tmp")),
                "the temporary file was left behind");
    }
}
