package dev.fmangoni.forge.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one dashboard preference that has to outlive the process.
 *
 * <p>Small enough to look not worth testing, which is how it stayed broken: the setting lived in a
 * field, every restart cleared it, and the queue stopped without anything saying so. The tests here
 * are about the two answers that matter — a fresh root starts nothing, and a root where it was
 * switched on stays switched on.
 */
class DashboardSettingsTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a root nobody has configured starts nothing")
    void freshRootIsOff() {
        // Launching an editor is not consent to spend a subscription.
        assertFalse(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("switching it on survives a restart")
    void survivesRestart() {
        new DashboardSettings(true).save(root);

        assertTrue(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("switching it off survives too")
    void offSurvivesRestart() {
        new DashboardSettings(true).save(root);
        new DashboardSettings(false).save(root);

        assertFalse(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("an unreadable file reads as off, which is the answer that spends nothing")
    void corruptFileIsOff() throws IOException {
        Files.writeString(root.resolve("dashboard.json"), "{ not json",
                StandardCharsets.UTF_8);

        assertFalse(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("a root that does not exist yet can still be written to")
    void createsItsOwnDirectory() {
        Path missing = root.resolve("somewhere/new");

        new DashboardSettings(true).save(missing);

        assertTrue(DashboardSettings.load(missing).isAutoRun());
    }
}
