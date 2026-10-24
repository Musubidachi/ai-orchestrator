package dev.fmangoni.forge.deploy.shelf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * That a family is shelved on GitHub, and where: kept beside each of its versions' states while
 * everything else of theirs is gone from this machine.
 *
 * <p>What makes a shelved project still a project here. Its card can say so and offer to resume it,
 * and Start refuses it, rather than seeing no repository and building it again from nothing.
 *
 * @param branch      the shelf on GitHub, such as {@code shelf/003}
 * @param commit      the shelf commit, which carries Forge's own files on top of the project
 * @param head        the project's own last commit, which the repository is put back to
 * @param localBranch the branch the repository was on
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelfMark(String family, String repository, String url, String branch, String commit,
                        String head, String localBranch, List<String> versions, Instant shelvedAt) {

    public static final String FILE = "shelved.json";

    /** Whether a version is shelved, and where; unreadable is an error, never "not shelved". */
    public static Optional<ShelfMark> of(Path runsDir, String id) {
        Path file = fileOf(runsDir, id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.mapper().readValue(JsonFile.read(file), ShelfMark.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static Path fileOf(Path runsDir, String id) {
        return RunPaths.under(runsDir, id).root().resolve(FILE);
    }

    /** Written beside every version's state, before anything of theirs is deleted. */
    void place(Path runsDir) {
        try {
            String json = Json.mapper().writeValueAsString(this);
            for (String version : versions) {
                Path file = fileOf(runsDir, version);
                Files.createDirectories(file.getParent());
                JsonFile.write(file, json);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot mark " + family + " as shelved", e);
        }
    }

    /** Taken away once the family is back. */
    void lift(Path runsDir) {
        try {
            for (String version : versions) {
                Files.deleteIfExists(fileOf(runsDir, version));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear " + family + "'s shelf marks", e);
        }
    }
}
