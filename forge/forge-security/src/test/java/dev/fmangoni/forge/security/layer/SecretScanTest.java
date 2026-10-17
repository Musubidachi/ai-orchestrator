package dev.fmangoni.forge.security.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretScanTest {

    @TempDir
    Path root;

    private Path repo;
    private Git git;

    /** Built at runtime so no credential-shaped literal sits in this source file. */
    private static final String AWS = "AKIA" + "Q7XK2M9PLR4TN8WZ";
    private static final String DELETED_AWS = "AKIA" + "ZZ3F8Q1KD0M2W7YB";
    private static final String GOOGLE = "AIza" + "Sy" + "A".repeat(33);

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(root.resolve("repo"));
        git = new Git(repo, root.resolve("git.log"));
        git.init();
    }

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<SecurityFinding> scan() {
        return new SecretScan(repo, git).run().findings();
    }

    @Test
    @DisplayName("a key in the tree, and one deleted from it but still in history, are both found")
    void treeAndHistory() throws Exception {
        write("config/old.properties", "aws.key=" + DELETED_AWS + "\n");
        git.commitAll("oops");
        Files.delete(repo.resolve("config/old.properties"));
        write("src/client.js", "const key = \"" + AWS + "\";\n");
        git.commitAll("removed the old key, added a new one");

        List<SecurityFinding> findings = scan();

        SecurityFinding current = findings.stream()
                .filter(f -> f.location().startsWith("src/client.js:1")).findFirst().orElseThrow();
        assertEquals(SecurityFinding.CRITICAL, current.severity());
        assertTrue(current.serious());
        SecurityFinding historic = findings.stream()
                .filter(f -> f.location().contains("config/old.properties")).findFirst().orElseThrow();
        assertTrue(historic.location().contains("still in history"), historic.location());
        assertFalse(historic.serious(), "no repair turn can take a key out of history; its owner revokes it");
        assertTrue(new SecurityReport("c", "t", List.of(LayerResult.ran(SecretScan.LAYER, "", findings)))
                .forRuling().contains(historic), "but it still needs a decision before accepting");

        String everything = findings.toString();
        assertFalse(everything.contains(AWS) || everything.contains(DELETED_AWS),
                "a report that repeats the key is one more place it leaked: " + everything);
    }

    @Test
    @DisplayName("a committed .env with values is a leak; a template, a blank one, and ignored trees are not")
    void environmentFiles() throws Exception {
        write(".env", "API_URL=https://api.example.com\nSESSION_SECRET=hunter22\nEMPTY=\n");
        write(".env.example", "SESSION_SECRET=changeme\n");
        write(".gitignore", "node_modules/\n");
        write("node_modules/lib/vendored.js", "var k = \"" + AWS + "\";\n");
        git.commitAll("config");

        List<SecurityFinding> findings = scan();

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(".env", findings.get(0).location());
        assertTrue(findings.get(0).evidence().contains("SESSION_SECRET"));
        assertFalse(findings.get(0).evidence().contains("hunter22"));
    }

    @Test
    @DisplayName("a Google browser key is a note to check its restrictions, not a leak")
    void googleKeysAreNotSerious() throws Exception {
        write("web/firebase.js", "apiKey: \"" + GOOGLE + "\",\n");
        write("docs/aws.md", "Use a key like AKIAIOSFODNN7EXAMPLE in the docs.\n");
        git.commitAll("web");

        List<SecurityFinding> findings = scan();

        assertEquals(1, findings.size(), "the documentation's example key is not a secret: " + findings);
        assertEquals(SecurityFinding.MEDIUM, findings.get(0).severity());
        assertFalse(findings.get(0).serious());
    }
}
