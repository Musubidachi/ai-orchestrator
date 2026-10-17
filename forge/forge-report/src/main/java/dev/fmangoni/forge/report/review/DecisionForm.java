package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The part of the report you act on rather than read.
 *
 * <p>Approval lives here, beside the claims it is a judgement about, because the alternative was a
 * button on a card next to a summary — and a yes given without the list in front of you is not
 * really a yes about anything. Each item is ruled on separately, so what goes back to the agent is
 * "this specific claim, and what I think of it" rather than a paragraph of general dissatisfaction.
 *
 * <p>Submitting is one act with two outcomes: everything standing accepts the project, anything
 * sent back starts a session. There is deliberately no Accept button separate from the list.
 *
 * <p>The form only works when the report is served by the editor. Opened straight off disk it
 * renders read-only, because a page on {@code file://} has no API to post to — and a submit button
 * that silently does nothing is worse than an honest absence of one.
 */
@UtilityClass
public class DecisionForm {

    /**
     * @param blocked why the project cannot be accepted yet, or blank when it can. The form is
     *                still rendered either way: a note is worth recording whatever the pipeline
     *                is waiting on.
     */
    /**
     * @param features the feature rulings, folded into this form rather than given one of their
     *                 own. Two forms meant two buttons, and pressing the first one started a
     *                 session that made the second refuse.
     * @param blocked  why the project cannot be accepted yet, or blank when it can. The form is
     *                 still rendered either way: a note is worth recording whatever the pipeline
     *                 is waiting on.
     */
    public String render(String projectId, List<ReviewItem> items, List<Decision> previous,
                         List<Decision> orphans, String features, String blocked) {
        return render(projectId, items, previous, orphans, features, blocked, List.of());
    }

    /**
     * @param history every review submitted so far. An item ruled in one of them — or recorded as
     *                ruled before the history was kept — has come back, and is shown with nothing
     *                ticked: a ruling is about the item as it stood when it was made, and carrying
     *                it forward pre-ticked is how an unfixed defect got waved through again.
     */
    public String render(String projectId, List<ReviewItem> items, List<Decision> previous,
                         List<Decision> orphans, String features, String blocked, List<Sitting> history) {
        String notice = blocked == null || blocked.isBlank()
                ? ""
                : "<p class=\"decide-blocked\">" + Html.escape(blocked) + "</p>";

        StringBuilder out = new StringBuilder("<h2>Your review</h2>\n");
        out.append(items.isEmpty()
                ? """
                <p class="muted">Nothing outstanding: the agent reported no unproven claims, \
                the reviewer raised nothing blocking, and no work is listed as remaining.</p>
                """
                : """
                <p class="muted">Rule on each of these. Fine as it is on everything accepts the \
                project; anything you send back becomes the next session's instructions and starts \
                it. Blocking findings, and anything that has come back since you last submitted, \
                start with nothing ticked and need a ruling. Notes are kept whichever you choose, \
                and are sent with it.</p>
                """);
        out.append(notice).append("<form id=\"decide\">\n").append(features);

        Map<String, Decision> earlier = byId(previous);
        for (ReviewItem item : items) {
            out.append(row(item, earlier.get(item.id()), cameBack(item, earlier, history)));
        }

        out.append("""
                <div class="decide-actions">
                  <button type="submit">%s</button>
                  <span id="decide-msg" class="muted"></span>
                </div>
                </form>
                """.formatted(items.isEmpty() ? "Submit" : "Submit review"));
        return out + past(orphans) + script(projectId);
    }

    private Map<String, Decision> byId(List<Decision> previous) {
        return previous.stream().collect(
                Collectors.toMap(Decision::getItemId, Function.identity(), (a, b) -> b));
    }

    /**
     * Notes on claims the agent no longer lists.
     *
     * <p>Read-only, and after the form, because there is nothing to rule on — the statement they
     * answered is gone. Shown at all because they are the part of this page a person wrote, and a
     * list that quietly shortens as the agent rewrites its own is not a record of anything.
     */
    private String past(List<Decision> orphans) {
        if (orphans == null || orphans.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("""
                <h3>Notes on things no longer listed</h3>
                <p class="muted">The agent rewrites what it has not proven at the end of every \
                session, and these claims are no longer among them. What you said about them is \
                kept here.</p>
                """);
        for (Decision decision : orphans) {
            out.append("<div class=\"decide-item decide-past\">\n")
                    .append("  <div class=\"decide-text\">")
                    .append(Html.escape(decision.getText()))
                    .append("</div>\n")
                    .append(Notes.thread(decision))
                    .append("</div>\n");
        }
        return out.toString();
    }

    /**
     * A line saying this item has been ruled on before, or null if it never has.
     *
     * <p>From the history when there is one, and otherwise from what was on record before the
     * history was kept.
     */
    public String cameBack(ReviewItem item, List<Decision> previous, List<Sitting> history) {
        return cameBack(item, byId(previous), history);
    }

    private String cameBack(ReviewItem item, Map<String, Decision> earlier, List<Sitting> history) {
        Optional<Sitting> sitting = ReviewHistory.lastSittingFor(history, item.id());
        if (sitting.isPresent()) {
            String verdict = ReviewHistory.lastRulingOn(history, item.id())
                    .map(Decision::getVerdict).orElse("");
            return "Came back — you ruled it " + Verdicts.said(verdict) + " on "
                    + Verdicts.when(sitting.get().getAt()) + ". See your past reviews below.";
        }
        Decision recorded = earlier.get(item.id());
        return recorded == null ? null
                : "Came back — you last ruled it " + Verdicts.said(recorded.getVerdict()) + ".";
    }

    /**
     * One item to rule on.
     *
     * <p>Nothing is ticked for a blocking finding or for anything that has come back. A blocking
     * finding is something every project must fix, and a default of "Fine as it is" let one be
     * accepted by not reading it; an item that came back is a new question, whatever was said last
     * time. Everything else still starts at "Fine as it is".
     */
    private String row(ReviewItem item, Decision previous, String cameBack) {
        boolean blank = cameBack != null || ReviewItem.BLOCKING.equals(item.source());
        String id = Html.escape(item.id());

        return """
                <div class="decide-item" data-kind="review" data-item="%s" data-text="%s">
                  <div class="decide-src">%s</div>
                  <div class="decide-text">%s</div>
                %s%s  <label><input type="radio" name="v-%s" value="ok"%s> Fine as it is</label>
                  <label><input type="radio" name="v-%s" value="changes"> Needs work</label>
                  <input class="decide-comment" type="text" value=""
                         placeholder="%s">
                </div>
                """.formatted(
                id, Html.escape(item.text()),
                Html.escape(item.source()), Html.escape(item.text()),
                cameBack == null ? "" : "  <div class=\"decide-back\">" + Html.escape(cameBack) + "</div>\n",
                Notes.thread(previous),
                id, blank ? "" : " checked",
                id,
                previous != null && previous.hasComments()
                        ? "Add another note (optional)"
                        : "What you think of this, and why (optional)");
    }

    /**
     * Posts the whole list at once.
     *
     * <p>One request rather than one per item: the outcome depends on the set — all standing means
     * accept — so a half-submitted list has no meaning the server could act on.
     */
    private String script(String projectId) {
        return """
                <script>
                (function () {
                  var form = document.getElementById("decide");
                  var msg = document.getElementById("decide-msg");
                  if (!form) { return; }
                  // Served from the editor, or opened straight off disk? Only one can post.
                  if (location.protocol === "file:") {
                    form.innerHTML = "<p class='muted'>Open this report from the Forge editor "
                      + "to review it.</p>";
                    return;
                  }
                  // Every note box takes a pasted or picked screenshot, saved where the agent the
                  // note goes to can open it. attach.js is the editor's, so only when served by it.
                  [].forEach.call(form.querySelectorAll("input[type=text]"), function (box) {
                    box.setAttribute("data-attach", "%1$s");
                  });
                  var attach = document.createElement("script");
                  attach.src = "/attach.js";
                  document.head.appendChild(attach);
                  form.addEventListener("submit", function (event) {
                    event.preventDefault();
                    try {
                      var payload = collect();
                      if (unruled(payload.decisions)) { return; }
                      send(payload);
                    } catch (e) {
                      // Anything thrown while reading the form used to leave the button doing
                      // nothing whatsoever: no message, no request, no error on screen. That is
                      // the worst way for a submit to fail, so it says so instead.
                      msg.textContent = "Could not submit: " + e;
                    }
                  });
                  // Selected by what a row IS rather than by what it looks like. Both kinds share
                  // .decide-item for styling, and so do the read-only notes on claims the agent no
                  // longer lists -- reading a comment box off one of those threw and took the whole
                  // submission with it, silently. data-kind cannot drift from the markup that way.
                  function rowsOf(kind, fallbackVerdict) {
                    return [].map.call(
                      form.querySelectorAll("[data-kind=" + kind + "]"), function (el) {
                        var picked = el.querySelector("input[type=radio]:checked");
                        var box = el.querySelector(".decide-comment");
                        return {
                          itemId: el.dataset.item,
                          text: el.dataset.text,
                          verdict: picked ? picked.value : fallbackVerdict,
                          comment: box ? box.value : ""
                        };
                      });
                  }
                  // A row with nothing ticked is not a ruling, and is not sent as one. Blocking
                  // findings and anything that came back start that way on purpose.
                  function unruled(decisions) {
                    var missing = 0;
                    [].forEach.call(form.querySelectorAll("[data-kind=review]"), function (el) {
                      var blank = !el.querySelector("input[type=radio]:checked");
                      el.classList.toggle("decide-missing", blank);
                      if (blank) { missing++; }
                    });
                    if (missing) {
                      msg.textContent = "Rule on the " + missing + (missing === 1 ? " item" : " items")
                        + " outlined in red first: Fine as it is, or Needs work.";
                      var first = form.querySelector(".decide-missing");
                      if (first && first.scrollIntoView) { first.scrollIntoView({block: "center"}); }
                    }
                    return missing > 0;
                  }
                  function collect() {
                    var decisions = rowsOf("review", "undecided");
                    if (!decisions.length) {
                      decisions = [{itemId: "none", text: "nothing outstanding", verdict: "ok"}];
                    }
                    // A feature nobody has answered stays unanswered: silence is not a decline,
                    // and asking again next time is cheaper than filming something unwanted or
                    // quietly dropping something wanted.
                    var features = rowsOf("feature", "undecided").filter(function (row) {
                      return row.verdict !== "undecided" || row.comment;
                    });

                    var added = form.querySelector(".feature-name");
                    var shows = form.querySelector(".feature-demo");
                    if (added && added.value.trim()) {
                      // Approved by definition -- you would not be typing it otherwise. The id is
                      // derived from the name on the server, so the name-to-filename rule lives in
                      // exactly one place.
                      features.push({
                        itemId: "",
                        text: added.value.trim(),
                        verdict: "approved",
                        comment: shows ? shows.value : ""
                      });
                    }
                    return {decisions: decisions, features: features};
                  }
                  function send(payload) {
                    msg.textContent = "Submitting...";
                    fetch("/api/jobs/decide", {
                      method: "POST",
                      headers: {"Content-Type": "application/json"},
                      body: JSON.stringify({
                        id: "%1$s",
                        decisions: payload.decisions,
                        features: payload.features
                      })
                    }).then(function (r) { return r.json(); })
                      .then(function (r) {
                        msg.textContent = r.result || r.error || "done";
                        setTimeout(function () { location.reload(); }, 1800);
                      })
                      .catch(function (e) { msg.textContent = String(e); });
                  }
                })();
                </script>
                """.formatted(projectId);
    }
}
