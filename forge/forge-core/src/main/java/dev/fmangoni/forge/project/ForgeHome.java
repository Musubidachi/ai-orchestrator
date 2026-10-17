package dev.fmangoni.forge.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The orchestrator's own directory layout.
 *
 * @param root the Forge root, holding the wishlist, the runs, and the index
 */
public record ForgeHome(Path root) {

    public Path wishlist() {
        return root.resolve("wishlist");
    }

    public Path runs() {
        return root.resolve("runs");
    }

    public Path houseRulesFile() {
        return root.resolve("house-rules.md");
    }

    /**
     * Procedures agents load when they need them, copied into every workspace beside the house rules.
     * Optional, unlike the rules: a skill only ever explains how to meet a contract the rules state.
     */
    public Path skills() {
        return root.resolve("skills");
    }

    /**
     * The standing constraints copied into every workspace.
     *
     * <p>Absence is fatal rather than defaulted to something empty: the house rules are what tell an
     * agent not to weaken the tests it is being judged by, and a run that silently proceeded without
     * them would produce work that looks the same and is worth considerably less.
     */
    public String houseRules() {
        Path file = houseRulesFile();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "missing " + file + " — run 'forge init' or restore it from the repository");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
