package dev.fmangoni.forge.deploy.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding gh, which is only ever looked for: nothing here installs it. */
class GitHubCliTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("gh is found where its installer put it, however the environment spells the folder's name")
    void foundWhateverTheCase() throws Exception {
        // Maven's test runner hands the JVM PROGRAMFILES and PROGRAMW6432, and Java's map of the
        // environment is case-sensitive: looking for ProgramFiles found nothing, and Forge said gh
        // was not installed while it was.
        Path gh = root.resolve("GitHub CLI").resolve("gh.exe");
        Files.createDirectories(gh.getParent());
        Files.writeString(gh, "");

        assertEquals(Optional.of(gh), GitHubCli.find(Map.of("PROGRAMW6432", root.toString())));
        assertEquals(Optional.of(gh), GitHubCli.find(Map.of("programfiles", root.toString())));
        assertEquals(Optional.empty(), GitHubCli.find(Map.of("PROGRAMDATA", root.toString())), "only the folders it installs to");
    }

    @Test
    @DisplayName("a PATH spelled any way is still the PATH")
    void pathInAnyCase() throws Exception {
        Path gh = root.resolve("bin").resolve(System.getProperty("os.name", "").startsWith("Windows") ? "gh.exe" : "gh");
        Files.createDirectories(gh.getParent());
        Files.writeString(gh, "");
        gh.toFile().setExecutable(true);

        assertEquals(Optional.of(gh), GitHubCli.find(Map.of("pAtH", gh.getParent().toString())));
    }
}
