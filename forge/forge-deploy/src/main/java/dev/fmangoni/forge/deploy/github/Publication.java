package dev.fmangoni.forge.deploy.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A family's repository on GitHub: where it is, what has been pushed there, and how the last try went.
 *
 * <p>Kept beside the repository, in {@code runs/NNN/github.json}. Its existence is what "the
 * repository is established" means: an enhancement may only begin once it is here.
 *
 * @param repository   {@code owner/name}, or null before one was made
 * @param url          its web address
 * @param establishedAt when Forge made it
 * @param branches     each version pushed, at the commit it was pushed to
 * @param outcome      how the last try went: {@code published}, {@code failed} or {@code refused}
 * @param detail       why it did not publish, in a sentence, or blank
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Publication(String repository, String url, Instant establishedAt, List<PublishedBranch> branches,
                          String outcome, String detail, Instant attemptedAt) {

    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";
    public static final String REFUSED = "refused";

    public Publication {
        branches = branches == null ? List.of() : List.copyOf(branches);
        detail = detail == null ? "" : detail;
    }

    /** No repository yet, and why not. */
    static Publication notYet(String outcome, String detail, Instant at) {
        return new Publication(null, null, null, List.of(), outcome, detail, at);
    }

    /** Whether a repository was made. A try that failed before it got that far leaves none. */
    public boolean established() {
        return repository != null && !repository.isBlank();
    }

    public Optional<PublishedBranch> branchOf(String project) {
        return branches.stream().filter(branch -> branch.project().equals(project)).findFirst();
    }

    /** Whether GitHub already has this version, on this branch, at this commit. */
    boolean has(String project, String branch, String commit) {
        return branchOf(project).filter(published -> published.branch().equals(branch)
                && published.commit().equals(commit)).isPresent();
    }

    Publication withBranch(PublishedBranch pushed) {
        List<PublishedBranch> all = new ArrayList<>(branches.stream()
                .filter(branch -> !branch.project().equals(pushed.project())).toList());
        all.add(pushed);
        return new Publication(repository, url, establishedAt, all, outcome, detail, attemptedAt);
    }

    Publication attempted(String result, String why, Instant at) {
        return new Publication(repository, url, establishedAt, branches, result, why, at);
    }

    /** The address git pushes to. */
    public String remote() {
        return url.endsWith(".git") ? url : url + ".git";
    }

    public static Optional<Publication> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.mapper().readValue(JsonFile.read(file), Publication.class));
        } catch (IOException e) {
            // Unreadable is not "none": treating it as absent would make a second repository.
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, Json.mapper().writeValueAsString(this));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
