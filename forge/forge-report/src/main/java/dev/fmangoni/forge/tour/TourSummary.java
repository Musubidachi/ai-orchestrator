package dev.fmangoni.forge.tour;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.Scene;
import dev.fmangoni.forge.walkthrough.Spoken;
import dev.fmangoni.forge.walkthrough.Walkthrough;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The chapter at the end, which says what all of that added up to.
 *
 * <p>For a project Forge built, this is the walkthrough — the same paragraph the dashboard reads
 * off the run's own files, with the verdict, what is not proven and what is waiting on a decision.
 * Having just heard how the code works, the thing worth hearing next is whether it is trusted, and
 * that is a question the source cannot answer and the run's state can.
 *
 * <p>For anything else — Forge itself, or someone's repository — there is no run to ask, so the
 * closing chapter is what the tour just covered: how much there was and how it divided up.
 */
@UtilityClass
public class TourSummary {

    public Chapter closing(Path root, List<Chapter> chapters) {
        String said = fromRun(root).orElseGet(() -> fromTour(chapters));
        return new Chapter("In summary", "what it all came to", List.of(Scene.spoken("In summary", said)));
    }

    /**
     * The walkthrough for a run, when the toured code is a project Forge built.
     *
     * <p>Recognised by the run's own files sitting beside the repository: a {@code repo} directory
     * with a {@code state.json} next to it is a run, and nothing else is.
     */
    private Optional<String> fromRun(Path root) {
        RunPaths paths = new RunPaths(root.getParent() == null ? root : root.getParent());
        if (!root.getFileName().toString().equals("repo") || !Files.isRegularFile(paths.stateFile())) {
            return Optional.empty();
        }
        Optional<RunState> state = RunStore.survey(paths.stateFile());
        return state.map(run -> Walkthrough.of(paths, run, ReviewStore.load(paths.reviewFile())));
    }

    private String fromTour(List<Chapter> chapters) {
        // The module chapters are the ones with no code on screen; every other chapter is a class.
        long classes = chapters.stream().filter(chapter -> !chapter.scenes().isEmpty())
                .filter(chapter -> !chapter.scenes().getFirst().code().empty())
                .count();
        long modules = chapters.size() - classes;
        int scenes = chapters.stream().mapToInt(chapter -> chapter.scenes().size()).sum();
        double minutes = chapters.stream().mapToDouble(Chapter::seconds).sum() / 60;
        return Spoken.sentence("That was " + Spoken.count(classes, "class")
                + " across " + Spoken.count(modules, "module")
                + ", explained in " + Spoken.count(scenes, "step"))
                + " " + Spoken.sentence("Said end to end it runs about "
                        + Spoken.count(Math.round(minutes), "minute"))
                + " Every sentence in it came from the code and the notes written beside it, so it"
                + " is as current as the files you have, and it costs nothing to hear again after"
                + " they change.";
    }
}
