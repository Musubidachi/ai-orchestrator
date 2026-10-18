package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Saying which features are worth a recording, inside the one form the page submits.
 *
 * <p>Folded in here rather than given a form of its own, which is how it started and which was
 * wrong in a way that only showed up in use: two forms meant two buttons, pressing the first one
 * reopened the project and started a session, and the second was then refused with "still running"
 * — so a review typed at the same time went nowhere. One submission, one outcome.
 *
 * <p>Collapsed by default. Most visits are about the review, the clips themselves are already up
 * the page where they can be watched, and a list of radio buttons for features that were settled
 * three sessions ago is noise between a reader and the thing they came to do.
 */
@UtilityClass
public class FeatureRulings {

    public String render(List<Feature> proposed, List<Decision> rulings) {
        return render(proposed, List.of(), rulings);
    }

    public String render(List<Feature> proposed, List<Feature> ideas, List<Decision> rulings) {
        List<Feature> listed = Features.listed(proposed, ideas, rulings);
        Map<String, Decision> byId = Features.byId(rulings);

        StringBuilder out = new StringBuilder("<details class=\"features-fold\"");
        // Open when there is something to answer, so a new proposal is not hidden behind a
        // disclosure triangle nobody has a reason to click.
        if (listed.stream().anyMatch(feature -> unanswered(byId.get(feature.id())))) {
            out.append(" open");
        }
        out.append("><summary>").append(Html.escape(summary(listed, byId))).append("</summary>\n");

        out.append("""
                <p class="muted">Approving is asking for a recording: the gate deletes them before \
                every run and will not accept the project until an acceptance command puts them \
                back, so a yes on something unfilmed reopens the project and spends a session. \
                Declining is a decision too, and is remembered. A note beside a feature you keep \
                goes to the agent, and the project is not accepted until a session has read it.</p>
                """);

        for (Feature feature : listed) {
            out.append(row(feature, byId.get(feature.id())));
        }
        return out.append(addYourOwn()).append("</details>\n").toString();
    }

    private String summary(List<Feature> listed, Map<String, Decision> byId) {
        long undecided = listed.stream()
                .filter(feature -> unanswered(byId.get(feature.id()))).count();
        if (listed.isEmpty()) {
            return "Features — nothing proposed yet; ask for one";
        }
        return undecided == 0
                ? "Features — all " + listed.size() + " ruled on"
                : "Features — " + undecided + " of " + listed.size() + " awaiting your answer";
    }

    private String row(Feature feature, Decision ruling) {
        String id = Html.escape(feature.id());
        boolean changes = Features.changes(ruling);
        boolean approved = Features.approved(ruling) && !changes;
        boolean declined = Features.declined(ruling);

        return """
                <div class="decide-item feature-row" data-kind="feature" data-item="%s" \
                data-text="%s">
                  <div class="decide-text">%s</div>
                %s  <label><input type="radio" name="f-%s" value="approved"%s> Worth seeing</label>
                  <label><input type="radio" name="f-%s" value="changes"%s> Needs changes</label>
                  <label><input type="radio" name="f-%s" value="declined"%s> Not worth a clip</label>
                  <input class="decide-comment" type="text" value="" placeholder="%s">
                </div>
                """.formatted(
                id, Html.escape(feature.name()), Html.escape(feature.name()),
                Notes.thread(ruling) + pending(ruling),
                id, approved ? " checked" : "",
                id, changes ? " checked" : "",
                id, declined ? " checked" : "",
                ruling != null && ruling.hasComments()
                        ? "Add another note (optional)"
                        : "How it should change, what to film, or why not (optional)");
    }

    /**
     * Says a note has not reached an agent yet.
     *
     * <p>003's note asking for a credit ad sat on this page for a day looking like something that
     * had been dealt with. Nothing on the page separated a request an agent had read from one no
     * agent had seen.
     */
    private String pending(Decision ruling) {
        return ruling != null && !Features.declined(ruling) && ruling.hasUnheardComments()
                ? "  <div class=\"muted decide-pending\">Not yet sent to an agent — submitting sends it.</div>\n"
                : "";
    }

    /**
     * Asking for a feature the agent did not think to propose.
     *
     * <p>Without this the list is whatever the agent happened to mention, and something it left
     * out is something you can never ask to see — which quietly makes the agent the author of the
     * list after all. What you type here is approved by definition: you would not be typing it
     * otherwise.
     */
    private String addYourOwn() {
        return """
                <div class="decide-item feature-add">
                  <div class="decide-src">Ask for one yourself</div>
                  <input class="feature-name" type="text"
                         placeholder="What it does, in a few words">
                  <input class="feature-demo decide-comment" type="text"
                         placeholder="What a recording of it should show (optional)">
                </div>
                """;
    }

    /**
     * Whether this one is still waiting for an answer.
     *
     * <p>Asked of the verdict rather than of whether a row exists. A note typed beside a feature
     * without picking either button stores a ruling with no verdict in it, and counting that as
     * answered would take the question off the page while leaving it unanswered.
     */
    private boolean unanswered(Decision ruling) {
        return !Features.approved(ruling) && !Features.declined(ruling);
    }
}
