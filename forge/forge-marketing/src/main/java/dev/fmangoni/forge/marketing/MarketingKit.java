package dev.fmangoni.forge.marketing;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Makes a project's marketing kit: one agent turn in a folder of its own, then a check of the result.
 *
 * <p>The folder is {@code runs/<id>/marketing}, beside the repository and never inside it, so the kit
 * cannot change the product and the product's history carries no ad copy. It is emptied of the
 * previous kit first: a file left from last time would pass a check it was never written for.
 */
public final class MarketingKit {

    private static final Duration CAP = Duration.ofMinutes(20);

    private final Agents agents;

    public MarketingKit(Agents agents) {
        this.agents = agents;
    }

    /** Why a kit cannot be made now, or null. */
    public static String blockedReason(RunState state) {
        return state == null || state.getPhase() != Phase.ACCEPTED
                ? "only an accepted project gets a marketing kit" : null;
    }

    public KitStatus make(Spec spec, RunPaths paths, RunState state, String commit) {
        Path dir = paths.marketingDir();
        clearPreviousKit(dir);

        List<Feature> approved = Features.owed(Features.knownTo(state.getPlan()),
                DecisionStore.load(paths.featuresFile()));
        Map<String, String> clips = copyRecordings(paths, approved, dir.resolve("media"));
        Deployment deployed = Deployment.lastSuccessful(paths.deploymentsFile());
        String deployUrl = deployed == null ? "" : deployed.url();
        String brief = MarketingBrief.of(spec, approved, clips, readDigest(paths), deployUrl);

        List<AgentKind> order = new ArrayList<>(agents.reviewersFor(state.getAssignedTo()));
        if (state.getAssignedTo() != null && agents.kinds().contains(state.getAssignedTo())) {
            order.add(state.getAssignedTo());
        }
        List<String> refused = new ArrayList<>();
        for (AgentKind kind : order) {
            Agent agent = agents.get(kind);
            AgentResult result = agent.run(AgentTask.writable(dir, brief, CAP, paths.logs()).using(spec.modelFor(kind)));
            if (result.blockedExternally()) {
                refused.add(kind + " " + result.outcome());
                continue;
            }
            List<KitProblem> problems = new KitCheck(dir,
                    approved.stream().map(Feature::id).collect(Collectors.toSet()), deployUrl).run();
            return save(paths, new KitStatus(Instant.now(), commit, kind.name(),
                    problems.isEmpty() ? "ready" : "problems",
                    problems.isEmpty() ? "" : problems.size() + " problem(s) to fix before using it", problems));
        }
        return save(paths, new KitStatus(Instant.now(), commit, "", "unavailable",
                refused.isEmpty() ? "no agent is installed" : "no model could be asked: " + String.join("; ", refused),
                List.of()));
    }

    private static KitStatus save(RunPaths paths, KitStatus status) {
        try {
            Files.createDirectories(paths.marketingDir());
            JsonFile.write(paths.marketingStatusFile(), Json.mapper().writeValueAsString(status));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + paths.marketingStatusFile(), e);
        }
        return status;
    }

    private static void clearPreviousKit(Path dir) {
        try {
            Files.createDirectories(dir);
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (!entry.getFileName().toString().equals("status.json")) {
                        deleteTree(entry);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear " + dir, e);
        }
    }

    /** Link-safe: a junction is removed as itself, never followed. */
    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther()) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteTree(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /** The newest usable clip of each approved feature, from the repository or the evidence archive. */
    private static Map<String, String> copyRecordings(RunPaths paths, List<Feature> approved, Path media) {
        Map<String, String> copied = new LinkedHashMap<>();
        for (Feature feature : approved) {
            Path clip = Features.recordingOf(paths.repo(), feature)
                    .or(() -> Features.archivedRecordingOf(paths.evidence(), feature))
                    .orElse(null);
            if (clip == null) {
                continue;
            }
            try {
                Files.createDirectories(media);
                Files.copy(clip, media.resolve(clip.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                copied.put(feature.id(), clip.getFileName().toString());
            } catch (IOException e) {
                System.err.println("could not copy " + clip + " into the marketing kit: " + e.getMessage());
            }
        }
        return copied;
    }

    private static String readDigest(RunPaths paths) {
        try {
            return Files.isRegularFile(paths.digestFile()) ? Files.readString(paths.digestFile()) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
