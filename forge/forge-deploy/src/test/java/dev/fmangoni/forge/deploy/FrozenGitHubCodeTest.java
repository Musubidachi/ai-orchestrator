package dev.fmangoni.forge.deploy;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the GitHub publishing and shelving code to the version proved against the real GitHub.
 *
 * <p>Every other GitHub test pushes into repositories on disk. {@code LiveGitHubTest} is the one
 * that reaches the account, and it does not run in verify.bat, so a change here could pass every
 * suite and still fail on GitHub, where a failure can lose a shelved project. So these files are
 * frozen: each one's fingerprint is kept in {@code frozen/github.sha256}, and any change fails the
 * build until the live test has passed again on the changed code and the list is updated on purpose.
 * The git tag {@code github-verified-2026-10-24} marks the commit that was proved first, to go back to.
 */
class FrozenGitHubCodeTest {

    private static final Path MAIN = Path.of("src/main/java/dev/fmangoni/forge/deploy");
    private static final Path TEST = Path.of("src/test/java/dev/fmangoni/forge/deploy");
    private static final Path LIST = Path.of("src/test/resources/frozen/github.sha256");

    private static final String HOW = """
            The GitHub publishing and shelving code is frozen: it was proved against the real GitHub by
            LiveGitHubTest, and a change to it must be proved there again before it can be kept.
              1. Run the live test on the changed code (it uses the private repository forge-selftest):
                 mvn test -pl forge-deploy -am -Dtest=LiveGitHubTest "-Dforge.live.github=true" "-Djacoco.skip=true"
              2. Only once it passes, replace forge-deploy/%s with the list below.
            To go back instead: git checkout github-verified-2026-10-24 -- <the file>
            """.formatted(LIST.toString().replace('\\', '/'));

    @Test
    @DisplayName("the GitHub code is the code that was proved against GitHub, or the live test has been run again")
    void unchangedSinceProved() throws Exception {
        Map<String, String> now = fingerprints();
        Map<String, String> kept = kept();
        if (now.equals(kept)) {
            return;
        }
        List<String> differences = new ArrayList<>();
        now.forEach((file, sha) -> {
            if (!kept.containsKey(file)) {
                differences.add("added:   " + file);
            } else if (!kept.get(file).equals(sha)) {
                differences.add("changed: " + file);
            }
        });
        kept.keySet().stream().filter(file -> !now.containsKey(file)).forEach(file -> differences.add("removed: " + file));
        StringBuilder list = new StringBuilder();
        now.forEach((file, sha) -> list.append(sha).append("  ").append(file).append('\n'));
        fail(HOW + "\n" + String.join("\n", differences) + "\n\n" + list);
    }

    /** What is frozen: both GitHub packages, what they call in this one, and the live test itself. */
    private static Map<String, String> fingerprints() throws Exception {
        List<Path> files = new ArrayList<>();
        files.addAll(javaIn(MAIN.resolve("github")));
        files.addAll(javaIn(MAIN.resolve("shelf")));
        files.add(MAIN.resolve("Executables.java"));
        files.add(MAIN.resolve("Readiness.java"));
        files.add(TEST.resolve("shelf/LiveGitHubTest.java"));
        Map<String, String> fingerprints = new TreeMap<>();
        for (Path file : files) {
            fingerprints.put(file.toString().replace('\\', '/'), sha256(file));
        }
        return fingerprints;
    }

    private static List<Path> javaIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Of the text with Windows line endings read as plain ones, so a checkout's settings change nothing. */
    private static String sha256(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> kept() throws IOException {
        Map<String, String> kept = new TreeMap<>();
        if (!Files.isRegularFile(LIST)) {
            return kept;
        }
        for (String line : Files.readAllLines(LIST, StandardCharsets.UTF_8)) {
            String strip = line.strip();
            if (strip.isEmpty() || strip.startsWith("#")) {
                continue;
            }
            int gap = strip.indexOf("  ");
            kept.put(strip.substring(gap + 2).strip(), strip.substring(0, gap));
        }
        return kept;
    }
}
