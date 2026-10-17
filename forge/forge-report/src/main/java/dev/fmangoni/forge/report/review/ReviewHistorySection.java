package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.Sitting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Every review you have submitted on this project, newest first: what you ruled, what you wrote,
 * and what happened next.
 *
 * <p>The form above only ever asks about what is outstanding now. Once submitted, a ruling moves
 * here, so the form never answers a question with last week's opinion — and so it is still there
 * to read when the same defect comes back.
 */
@UtilityClass
public class ReviewHistorySection {

    /**
     * @param stored rulings on record before the history was kept, shown once at the bottom so
     *               they are not lost; anything also in a submitted sitting is left out of it
     */
    public String render(List<Sitting> history, List<Decision> stored) {
        List<Decision> earlier = beforeTheHistory(history, stored);
        if (history.isEmpty() && earlier.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("""
                <h3 id="history">Your past reviews</h3>
                <p class="muted">Each submission, as you made it. An item that comes back after \
                this appears above with nothing ticked.</p>
                <div class="history-wrap"><table class="history">
                <thead><tr><th>Submitted</th><th>Item</th><th>You said</th><th>Your note</th>\
                <th>What happened</th></tr></thead>
                <tbody>
                """);
        List<Sitting> newestFirst = new ArrayList<>(history);
        Collections.reverse(newestFirst);
        for (Sitting sitting : newestFirst) {
            List<Decision> rows = new ArrayList<>(sitting.getRulings());
            sitting.getFeatures().forEach(feature -> rows.add(Decision.of(feature.getItemId(),
                    "Feature: " + feature.getText(), feature.getVerdict(), feature.getComment())));
            for (int i = 0; i < rows.size(); i++) {
                boolean first = i == 0;
                out.append(row(first ? Verdicts.when(sitting.getAt()) : "", rows.get(i),
                        first ? sitting.getOutcome() : ""));
            }
        }
        for (int i = 0; i < earlier.size(); i++) {
            out.append(row(i == 0 ? "before this history was kept" : "", earlier.get(i), ""));
        }
        return out.append("</tbody></table></div>\n").toString();
    }

    private String row(String when, Decision ruling, String outcome) {
        String note = ruling.getComment() != null && !ruling.getComment().isBlank()
                ? ruling.getComment() : ruling.latestComment();
        return "<tr><td class=\"history-when\">" + Html.escape(when) + "</td>"
                + "<td>" + Html.escape(ruling.getText()) + "</td>"
                + "<td>" + Html.escape(Verdicts.said(ruling.getVerdict())) + "</td>"
                + "<td>" + Html.escape(note == null ? "" : note) + "</td>"
                + "<td>" + Html.escape(outcome == null ? "" : outcome) + "</td></tr>\n";
    }

    private List<Decision> beforeTheHistory(List<Sitting> history, List<Decision> stored) {
        Set<String> inHistory = ReviewHistory.ruled(history);
        List<Decision> out = new ArrayList<>();
        for (Decision decision : stored == null ? List.<Decision>of() : stored) {
            if (!inHistory.contains(decision.getItemId()) && !"none".equals(decision.getItemId())) {
                out.add(decision);
            }
        }
        return out;
    }
}
