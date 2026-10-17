package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * What the agent thinks the project could do next, for a yes or a no.
 *
 * <p>Part of the one form, beside the features, and stored the same way — an approved idea is a
 * request to build that feature, so it takes the feature's id, turns up in the gallery as owed, and
 * is filmed like any other once it exists. Declined ideas are remembered, so the same suggestion
 * coming back next session arrives already answered.
 *
 * <p>Only ideas that are not features already. Once one has been approved it belongs to the
 * features list, and showing it here as well would offer two sets of buttons for one decision.
 */
@UtilityClass
public class IdeaRulings {

    public String render(List<Feature> ideas, List<Feature> listedFeatures,
                         List<Decision> rulings) {
        Set<String> alreadyFeatures = new HashSet<>();
        listedFeatures.forEach(feature -> alreadyFeatures.add(feature.id()));
        List<Feature> open = ideas.stream()
                .filter(idea -> !alreadyFeatures.contains(idea.id()))
                .toList();
        if (open.isEmpty()) {
            return "";
        }
        Map<String, Decision> byId = Features.byId(rulings);
        long unanswered = open.stream()
                .filter(idea -> !Features.declined(byId.get(idea.id()))).count();

        StringBuilder out = new StringBuilder("<details class=\"features-fold ideas-fold\"");
        if (unanswered > 0) {
            out.append(" open");
        }
        out.append("><summary>")
                .append(Html.escape(unanswered == 0
                        ? "Ideas for what next — all " + open.size() + " set aside"
                        : "Ideas for what next — " + unanswered + " suggested"))
                .append("</summary>\n")
                .append("""
                        <p class="muted">Suggested by the agent now that the work is in place. \
                        Build it asks the next session to add the feature and film it; Not now is \
                        remembered, so it will not be asked about again.</p>
                        """);

        for (Feature idea : open) {
            out.append(row(idea, byId.get(idea.id())));
        }
        return out.append("</details>\n").toString();
    }

    private String row(Feature idea, Decision ruling) {
        String id = Html.escape(idea.id());
        return """
                <div class="decide-item feature-row" data-kind="feature" data-item="%s" \
                data-text="%s">
                  <div class="decide-text">%s</div>
                %s%s  <label><input type="radio" name="f-%s" value="approved"> Build it</label>
                  <label><input type="radio" name="f-%s" value="declined"%s> Not now</label>
                  <input class="decide-comment" type="text" value=""
                         placeholder="How you would want it, or why not (optional)">
                </div>
                """.formatted(
                id, Html.escape(idea.name()), Html.escape(idea.name()),
                idea.demo().isBlank() ? ""
                        : "  <div class=\"decide-src\">" + Html.escape(idea.demo()) + "</div>\n",
                Notes.thread(ruling),
                id, id, Features.declined(ruling) ? " checked" : "");
    }
}
