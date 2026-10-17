package dev.fmangoni.forge.report.deck;

import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Turns one review item into the card that asks about it.
 *
 * <p>The item's text is what the form shows and what the ruling is filed under, and it is written
 * for whoever will fix the thing. The card leads with the reviewer's plain line instead when there
 * is one, and keeps the original a click away. A finding from before reviewers were asked for a
 * plain line has none, and its summary stands in — which reads no better than the form did, and no
 * worse.
 */
@UtilityClass
class Cards {

    /** The stakes words a reviewer is offered; anything else it wrote is not shown as a tag. */
    private final List<String> STAKES = List.of("cosmetic", "wrong result", "data loss", "security", "upkeep");

    /**
     * @param finding  the reviewer's finding behind a blocking item, as the run state carries it —
     *                 with its attempts counted — or null for any other kind of item
     * @param security the security finding behind a security item, or null
     * @param previous what the person last said about it, or null
     * @param cameBack the line saying it has been ruled on before, or null
     */
    Card of(ReviewItem item, Finding finding, SecurityFinding security, Decision previous, String cameBack) {
        Card.CardBuilder card = Card.builder()
                .id(item.id())
                .kind(kindOf(item))
                .source(item.source())
                .text(item.text())
                .headline(item.text())
                .detail("")
                .stakes("")
                .cameBack(cameBack == null ? "" : cameBack)
                .attempts(0)
                .stillPresent("")
                .notes(notesOn(previous));
        if (finding != null) {
            blocking(card, finding);
        }
        if (security != null) {
            security(card, security);
        }
        return card.build();
    }

    /** The prefix of the item's id, which is how the form tells the kinds apart too. */
    private String kindOf(ReviewItem item) {
        int dash = item.id().lastIndexOf('-');
        return dash < 0 ? item.id() : item.id().substring(0, dash);
    }

    private void blocking(Card.CardBuilder card, Finding finding) {
        boolean plain = !blank(finding.getPlain());
        StringBuilder detail = new StringBuilder();
        if (plain) {
            detail.append(ReviewItem.textOf(finding));
        } else if (!blank(finding.getFile())) {
            detail.append(finding.getFile());
        }
        if (!blank(finding.getDetail())) {
            detail.append(detail.isEmpty() ? "" : "\n\n").append(finding.getDetail().strip());
        }
        card.headline(plain ? finding.getPlain().strip() : finding.getSummary())
                .detail(detail.toString())
                .stakes(stakesOf(finding.getStakes()))
                .attempts(finding.getAttempts())
                .stillPresent(blank(finding.getStillPresent()) ? "" : finding.getStillPresent().strip());
    }

    private void security(Card.CardBuilder card, SecurityFinding finding) {
        StringBuilder detail = new StringBuilder(finding.severity() + " severity, found by " + finding.layer());
        if (!blank(finding.location())) {
            detail.append("\n").append(finding.location());
        }
        if (!blank(finding.evidence())) {
            detail.append("\n\n").append(finding.evidence());
        }
        card.headline(finding.title()).detail(detail.toString()).stakes("security");
    }

    private String stakesOf(String said) {
        String word = said == null ? "" : said.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        return STAKES.contains(word) ? word : "";
    }

    private List<String> notesOn(Decision previous) {
        return Optional.ofNullable(previous).map(Decision::getComments).orElse(List.of()).stream()
                .map(Comment::text)
                .filter(text -> !blank(text))
                .toList();
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
