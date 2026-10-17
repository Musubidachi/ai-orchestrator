package dev.fmangoni.forge.report.deck;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.review.DecisionForm;
import dev.fmangoni.forge.report.review.DecisionSection;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Deals a project's review as cards, for the quick review page.
 *
 * <p>The same items as the form in the report, under the same ids, read from the same files, and
 * submitted to the same place. Nothing about what a ruling means is decided here: this only chooses
 * the order, and which items a person has to answer one at a time. The rule for that is the form's
 * rule for what starts with nothing ticked — a blocking finding, or anything that has come back —
 * with security findings added, because a card that can be waved through in a batch is the wrong
 * place for one of those.
 */
@UtilityClass
public class Deck {

    /**
     * @param runsDir what the editor serves as {@code /runs/}
     * @param review  the cross-review, or null when none has run
     */
    public DeckView of(Path runsDir, RunPaths paths, RunState state, Review review) {
        SecurityReport security = SecurityReport.load(paths.securityFile());
        List<ReviewItem> items = ReviewItem.of(state.getPlan(), review, security);
        List<Decision> stored = Decisions.realign(items, DecisionStore.load(paths.decisionsFile()));
        List<Sitting> history = ReviewHistory.load(paths.reviewHistoryFile());
        Map<String, Decision> earlier = stored.stream()
                .collect(Collectors.toMap(Decision::getItemId, Function.identity(), (a, b) -> b));

        List<Card> mustDecide = new ArrayList<>();
        List<Card> skim = new ArrayList<>();
        for (ReviewItem item : items) {
            String cameBack = DecisionForm.cameBack(item, stored, history);
            Card card = Cards.of(item, findingBehind(item, state, review), securityBehind(item, security),
                    earlier.get(item.id()), cameBack);
            boolean answeredAlone = cameBack != null
                    || ReviewItem.BLOCKING.equals(item.source())
                    || ReviewItem.SECURITY.equals(item.source());
            (answeredAlone ? mustDecide : skim).add(card);
        }

        List<Decision> rulings = DecisionStore.load(paths.featuresFile());
        Map<String, Decision> ruled = Features.byId(rulings);
        List<Feature> ideas = Feature.ideas(state.getPlan());
        List<Feature> listed = Features.listed(Feature.of(state.getPlan()), ideas, rulings);
        List<FeatureCard> features = listed.stream()
                .filter(feature -> unanswered(ruled.get(feature.id())))
                .map(feature -> FeatureCards.of(feature, ruled.get(feature.id()), paths.repo(),
                        paths.evidence(), runsDir))
                .toList();

        return DeckView.builder()
                .id(state.getId())
                .title(state.getTitle() == null ? state.getId() : state.getTitle())
                .phase(state.getPhase().name())
                .notice(DecisionSection.notice(state, review))
                .mustDecide(mustDecide)
                .features(features)
                .skim(skim)
                .featuresAlreadyRuled(listed.size() - features.size())
                .ideasWaiting((int) ideas.stream().filter(idea -> unanswered(ruled.get(idea.id()))).count())
                .report("/runs/" + state.getId() + "/report.html")
                .build();
    }

    /**
     * The reviewer's finding a blocking item was made from.
     *
     * <p>From the run state when it is there, because that copy has been carried through the review
     * rounds and knows how many attempts it has had and why the last one fell short.
     */
    private Finding findingBehind(ReviewItem item, RunState state, Review review) {
        if (!ReviewItem.BLOCKING.equals(item.source())) {
            return null;
        }
        List<Finding> candidates = new ArrayList<>();
        if (state.getBlockingFindings() != null) {
            candidates.addAll(state.getBlockingFindings());
        }
        if (review != null) {
            candidates.addAll(review.blocking());
        }
        return candidates.stream()
                .filter(finding -> item.id().equals(ReviewItem.idOf(finding)))
                .findFirst().orElse(null);
    }

    private SecurityFinding securityBehind(ReviewItem item, SecurityReport security) {
        if (security == null || !ReviewItem.SECURITY.equals(item.source())) {
            return null;
        }
        // By the whole line, location included: two findings can share a title — two keys committed
        // in two files — and matching on the title gave the second card the first one's location.
        return security.forRuling().stream()
                .filter(finding -> item.text().equals(ReviewItem.textOf(finding)))
                .findFirst().orElse(null);
    }

    /** Neither kept nor dropped. A note with no verdict beside it is still a question unanswered. */
    private boolean unanswered(Decision ruling) {
        return !Features.approved(ruling) && !Features.declined(ruling);
    }
}
