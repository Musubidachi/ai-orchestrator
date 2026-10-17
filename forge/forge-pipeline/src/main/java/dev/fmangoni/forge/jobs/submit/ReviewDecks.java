package dev.fmangoni.forge.jobs.submit;

import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.deck.Deck;
import dev.fmangoni.forge.report.deck.DeckView;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the project a quick review is about and deals its cards.
 *
 * <p>Here, beside {@link Submissions}, because the two are the halves of one sitting: this is what
 * the page is shown, and that is where its answers go. Both find the project the same way, so a
 * review of an improvement reads the same files a submission about it will write.
 */
public final class ReviewDecks {

    private final Projects projects;
    private final Path runsDir;

    public ReviewDecks(Projects projects, Path runsDir) {
        this.projects = projects;
        this.runsDir = runsDir;
    }

    /** The project's cards, or empty when there is no such spec or it has never run. */
    public Optional<DeckView> of(String id) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RunPaths paths = found.get().paths();
        return RunStore.survey(paths.stateFile())
                .map(state -> Deck.of(runsDir, paths, state, ReviewStore.load(paths.reviewFile())));
    }
}
