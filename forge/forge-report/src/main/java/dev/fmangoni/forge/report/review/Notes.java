package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.experimental.UtilityClass;

/**
 * What has already been said about one item, wherever it is being shown.
 *
 * <p>Shared between the review form and the feature form because a note behaves the same in both:
 * it is displayed above the input rather than inside it. Putting the last remark back into the
 * text box reads as a draft to be edited, and quietly means only one remark can ever exist —
 * submitting again replaces it. A note is a thing that was said; it belongs on the record next to
 * whatever it was about.
 */
@UtilityClass
public class Notes {

    public String thread(Decision previous) {
        if (previous == null || !previous.hasComments()) {
            return "";
        }
        StringBuilder out = new StringBuilder("  <div class=\"decide-thread\">\n");
        for (Comment comment : previous.getComments()) {
            out.append("    <div class=\"decide-note\"><span class=\"decide-when\">")
                    .append(when(comment.at()))
                    .append("</span>")
                    .append(Html.escape(comment.text()))
                    .append("</div>\n");
        }
        return out.append("  </div>\n").toString();
    }

    /**
     * The date a note was left, or nothing for one carried over from before they were dated.
     *
     * <p>Date only. The hour a remark was typed has never mattered; which sitting it belongs to
     * does, and that is what the day gives you.
     */
    private String when(Instant at) {
        return at == null ? "" : LocalDate.ofInstant(at, ZoneId.systemDefault()) + " · ";
    }
}
