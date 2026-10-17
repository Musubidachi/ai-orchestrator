package dev.fmangoni.forge.ui.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * New project ideas from an idea generator Forge built, for the editor's description box.
 *
 * <p>Which program that is lives in {@value #CONFIG} beside the wishlist, so nothing here knows
 * about 006 in particular:
 *
 * <pre>{"project": "006", "mainClass": "com.passiveincome.IdeaGeneratorApp",
 *  "categories": ["browser extension", "cli tool"]}</pre>
 *
 * <p>It is run from the project's compiled classes, in a folder of its own that is deleted
 * afterwards, because the generator writes its report files into whatever folder it starts in, and
 * that must never be the project's repository. It is asked for one idea in JSON, with a fresh seed
 * each time, which is what makes a reroll a different idea. It is the person's own program running on
 * their own machine, with no model and no network involved.
 */
public final class IdeaSource {

    public static final String CONFIG = "idea-generator.json";

    /** It answers in well under a second; this is room for a cold JVM on a busy machine. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String project;
    private final String mainClass;
    private final List<String> categories;
    private final Path classes;

    private IdeaSource(String project, String mainClass, List<String> categories, Path classes) {
        this.project = project;
        this.mainClass = mainClass;
        this.categories = categories;
        this.classes = classes;
    }

    /**
     * The generator named beside the wishlist, or empty when none is named. A config that cannot be
     * read is reported and treated as none: it costs the buttons, never the editor.
     *
     * @param home    Forge's home, where the wishlist and its settings live
     * @param runsDir where the projects' runs are, the generator's among them
     */
    public static Optional<IdeaSource> load(Path home, Path runsDir) {
        Path file = home.resolve(CONFIG);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode config = Json.mapper().readTree(Files.readString(file));
            String project = config.path("project").asText("");
            String mainClass = config.path("mainClass").asText("");
            if (project.isBlank() || mainClass.isBlank()) {
                System.err.println("Ignoring " + file + ": it needs a project and a mainClass");
                return Optional.empty();
            }
            List<String> categories = new ArrayList<>();
            config.path("categories").forEach(c -> categories.add(c.asText()));
            return Optional.of(new IdeaSource(project, mainClass, List.copyOf(categories),
                    runsDir.resolve(project).resolve("repo").resolve("target").resolve("classes")));
        } catch (IOException e) {
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public String project() {
        return project;
    }

    public List<String> categories() {
        return categories;
    }

    /**
     * One new idea, as the generator wrote it.
     *
     * @param category  only ideas of this category, or blank for any
     * @param maxEffort only ideas at or under this effort, 1 to 5, or blank for any
     * @throws IllegalStateException with a sentence for the person when no idea could be had
     */
    public JsonNode next(String category, String maxEffort) {
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException(project + " has not been compiled, so there is nothing to run: "
                    + "build it once (Run Another Session, or mvn compile in its repository)");
        }
        List<String> args = new ArrayList<>(List.of("-cp", classes.toString(), mainClass,
                "--count", "1", "--seed", Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)),
                "--format", "json"));
        if (category != null && !category.isBlank()) {
            args.addAll(List.of("--category", category.strip()));
        }
        if (maxEffort != null && !maxEffort.isBlank()) {
            args.addAll(List.of("--max-effort", maxEffort.strip()));
        }
        Path scratch = scratch();
        try {
            ProcResult result = CliRunner.run(java(), args, scratch, null, TIMEOUT, scratch.resolve("idea.log"));
            Path out = scratch.resolve("out.json");
            if (!result.ok() || !Files.isRegularFile(out)) {
                throw new IllegalStateException(project + " did not produce an idea: " + lastLine(result));
            }
            JsonNode ideas = Json.mapper().readTree(Files.readString(out));
            if (!ideas.isArray() || ideas.isEmpty()) {
                throw new IllegalStateException(project + " produced no idea for those filters");
            }
            return ideas.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(project + "'s idea could not be read: " + e.getMessage(), e);
        } finally {
            delete(scratch);
        }
    }

    /** The Java Forge itself runs on: there is no other this machine is known to have. */
    private static Path java() {
        return ProcessHandle.current().info().command().map(Path::of).orElse(Path.of("java"));
    }

    private static String lastLine(ProcResult result) {
        String[] lines = result.combined().strip().split("\\R");
        return lines.length == 0 || lines[lines.length - 1].isBlank()
                ? "it exited with " + result.exitCode() : lines[lines.length - 1].strip();
    }

    private static Path scratch() {
        try {
            return Files.createTempDirectory("forge-idea");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make a folder to run the idea generator in", e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            System.err.println("could not tidy " + dir + ": " + e.getMessage());
        }
    }
}
