package dev.fmangoni.forge.report.deck;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * A feature as a card: its name, what it should show, and the clip to judge it by.
 *
 * <p>The clip is the point. A feature is ruled on by whether it is worth seeing, and the form asked
 * that beside a name, with the recordings further up the page. Here the recording is the card.
 */
@UtilityClass
class FeatureCards {

    /**
     * @param runsDir     what the editor serves as {@code /runs/}, so a clip's address can be worked out
     * @param evidenceDir where recordings from earlier sessions are kept
     */
    FeatureCard of(Feature feature, Decision ruling, Path repo, Path evidenceDir, Path runsDir) {
        FeatureCard.FeatureCardBuilder card = FeatureCard.builder()
                .id(feature.id())
                .name(feature.name())
                .demo(feature.demo() == null ? "" : feature.demo())
                .clip("")
                .clipKind("")
                .clipNote("No recording yet. Keeping it asks for one, which takes a session.")
                .notes(notesOn(ruling));

        RecordingTake take = Features.takeOf(repo, feature);
        if (take.usable()) {
            return playing(card, take.file(), runsDir, "Recorded by the last gate run.").build();
        }
        Optional<Path> archived = Features.archivedRecordingOf(evidenceDir, feature);
        if (archived.isPresent()) {
            return playing(card, archived.get(), runsDir, "From an earlier session. The last build did not "
                    + "produce this one: " + take.problem() + ".").build();
        }
        return card.build();
    }

    /** The same three states the report's gallery keeps apart: fresh, old, and nothing. */
    private FeatureCard.FeatureCardBuilder playing(FeatureCard.FeatureCardBuilder card, Path clip,
                                                   Path runsDir, String note) {
        String address = "/runs/" + runsDir.toAbsolutePath().normalize()
                .relativize(clip.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return card.clip(address)
                .clipKind(address.endsWith(".gif") ? "image" : "video")
                .clipNote(note);
    }

    private List<String> notesOn(Decision ruling) {
        return Optional.ofNullable(ruling).map(Decision::getComments).orElse(List.of()).stream()
                .map(Comment::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }
}
