package dev.fmangoni.forge.status.league;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * The vendors, ranked by what the runs on disk say each of them did.
 *
 * <p>Read fresh from {@code state.json} and {@code review.json} every time, with nothing kept: the
 * table cannot disagree with the projects it is about, and there is no score file to drift, reset
 * or be edited. The cost is that it knows only what those files still hold — a review is
 * overwritten by the next one, so a vendor's reviewing record is its latest review of each project.
 *
 * <p><b>Nothing reads this but the dashboard.</b> It does not choose which vendor builds the next
 * project, it is not shown to an agent, and no brief mentions it. A score an agent can see is a
 * score an agent will work towards, and the only thing here that is allowed to decide anything
 * about a project is the gate.
 */
@UtilityClass
public class League {

    /** Best first; a tie goes to the vendor that finished more, then by name so the order holds still. */
    private final Comparator<Standing> ORDER = Comparator.comparingInt(Standing::getPoints).reversed()
            .thenComparing(Comparator.comparingInt(Standing::getFinished).reversed())
            .thenComparing(Standing::getVendor);

    /** Every vendor that has built or reviewed anything under {@code runsDir}. */
    public List<Standing> of(Path runsDir) {
        Map<AgentKind, Tally> tallies = new EnumMap<>(AgentKind.class);
        for (String id : runIds(runsDir)) {
            RunPaths paths = RunPaths.under(runsDir, id);
            RunStore.survey(paths.stateFile()).ifPresent(state -> count(state, paths, tallies));
        }
        return tallies.entrySet().stream()
                .map(entry -> entry.getValue().standing(entry.getKey().name()))
                .sorted(ORDER)
                .toList();
    }

    private void count(RunState state, RunPaths paths, Map<AgentKind, Tally> tallies) {
        if (state.getAssignedTo() != null) {
            tallies.computeIfAbsent(state.getAssignedTo(), kind -> new Tally()).built(state);
        }
        Review review = ReviewStore.load(paths.reviewFile());
        if (review == null || review.isUnavailable() || review.getParseError() != null) {
            return;
        }
        reviewerOf(review).ifPresent(kind -> tallies.computeIfAbsent(kind, k -> new Tally()).reviewed(review));
    }

    /** The vendor in a reviewer line such as {@code CODEX (gpt-6-astra)}. */
    private Optional<AgentKind> reviewerOf(Review review) {
        String reviewer = review.getReviewer();
        if (reviewer == null || reviewer.isBlank()) {
            return Optional.empty();
        }
        String name = reviewer.strip().split("[\\s(]", 2)[0].toUpperCase(Locale.ROOT);
        return Stream.of(AgentKind.values()).filter(kind -> kind.name().equals(name)).findFirst();
    }

    private List<String> runIds(Path runsDir) {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(runsDir)) {
            return entries.filter(Files::isDirectory)
                    .map(entry -> entry.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("could not list " + runsDir + ": " + e.getMessage());
            return List.of();
        }
    }
}
