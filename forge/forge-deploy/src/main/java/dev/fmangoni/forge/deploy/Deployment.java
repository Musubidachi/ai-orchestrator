package dev.fmangoni.forge.deploy;

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

/**
 * One press of Deploy, as it went.
 *
 * @param number    1 for the first deploy of this project
 * @param commit    what was deployed
 * @param dirty     whether the working tree had uncommitted changes, which a deploy would have shipped
 * @param outcome   {@code deployed}, {@code failed}, {@code not-logged-in} or {@code unhealthy}
 * @param detail    what went wrong, in a sentence, or blank
 * @param url       where it ended up, or blank when no URL was given or printed
 * @param smoke     {@code ok}, {@code failed} or {@code skipped}
 * @param log       the full output of the deploy command
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Deployment(int number, String commit, boolean dirty, Instant startedAt, Instant finishedAt,
                         int exitCode, String outcome, String detail, String url, String smoke, String log) {

    public static final String DEPLOYED = "deployed";
    public static final String FAILED = "failed";
    public static final String NOT_LOGGED_IN = "not-logged-in";
    public static final String UNHEALTHY = "unhealthy";

    public boolean succeeded() {
        return DEPLOYED.equals(outcome);
    }

    /** Every deploy of a project, oldest first. */
    public static List<Deployment> history(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return List.of(Json.mapper().readValue(JsonFile.read(file), Deployment[].class));
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    public static void append(Path file, Deployment deployment) {
        List<Deployment> all = new ArrayList<>(history(file));
        all.add(deployment);
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, Json.mapper().writeValueAsString(all));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** The newest deploy that worked, or null. */
    public static Deployment lastSuccessful(Path file) {
        List<Deployment> all = history(file);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).succeeded()) {
                return all.get(i);
            }
        }
        return null;
    }
}
