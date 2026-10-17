package dev.fmangoni.forge.jobs.button;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.jobs.work.Eligibility;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pressing Play on a card.
 *
 * <p>None of these opens a window: the part that does is replaced with a list of what would have
 * been opened. What is tested is everything around it — which folder, and when the answer is no.
 */
class PlayTest {

    @TempDir
    Path root;

    private ForgeHome home;
    private JobPool pool;
    private final List<Path> opened = new ArrayList<>();
    private Play play;

    @BeforeEach
    void setUp() throws IOException {
        home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.createDirectories(home.runs());
        Files.writeString(home.wishlist().resolve("001-center-defense.md"), """
                ---
                id: "001"
                title: Center Defense
                acceptance:
                  - echo ok
                ---

                ## Goal
                A game.
                """);
        pool = new JobPool(2);
        play = new Play(new Projects(home, new Eligibility(home.runs())), pool, opened::add);
    }

    private Path repo() throws IOException {
        return Files.createDirectories(RunPaths.under(home.runs(), "001").repo());
    }

    private void giveItARunFile() throws IOException {
        Files.writeString(repo().resolve(RunFile.NAME), "@echo off\ncall npm run dev\n");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Play opens the run file from the project's own folder, and says how to stop it")
    void opensTheRunFile() throws IOException {
        giveItARunFile();

        String answer = play.start("001");

        assertEquals(List.of(repo()), opened);
        assertTrue(answer.contains("Center Defense") && answer.contains("Close that window"), answer);
    }

    @Test
    @DisplayName("a project with no run file opens nothing, and says when it will get one")
    void nothingToPlayWithoutARunFile() throws IOException {
        repo();

        String answer = play.start("001");

        assertEquals(List.of(), opened);
        assertTrue(answer.contains("has no run.bat yet"), answer);
    }

    @Test
    @DisplayName("a project being worked on is not played underneath its agent")
    void refusedWhileTheProjectIsRunning() throws Exception {
        giveItARunFile();
        CountDownLatch working = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.submit("001", worker -> {
            working.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> { });
        assertTrue(working.await(20, TimeUnit.SECONDS));

        try {
            String answer = play.start("001");

            assertEquals(List.of(), opened);
            assertTrue(answer.contains("is being worked on"), answer);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("an id that is not in the wishlist opens nothing")
    void unknownProject() {
        assertEquals("no spec with id 999", play.start("999"));
        assertEquals(List.of(), opened);
    }
}
