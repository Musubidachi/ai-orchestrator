package dev.fmangoni.forge.report.review;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import java.util.List;
import lombok.experimental.UtilityClass;

/** What the other model found, blocking first, and who it was. */
@UtilityClass
public class ReviewSection {

    public String render(Review review) {
        if (review == null) {
            return "";
        }
        if (review.getParseError() != null) {
            return "<h2>Cross-review</h2>" + byWhom(review) + "<p class=\"muted\">Unavailable: "
                    + Html.escape(review.getParseError()) + "</p>" + claims(review);
        }
        if (review.getFindings().isEmpty()) {
            return "<h2>Cross-review</h2>" + byWhom(review) + "<p class=\"muted\">No findings.</p>"
                    + claims(review);
        }
        return "<h2>Cross-review</h2>" + byWhom(review)
                + findings("Blocking", review.blocking())
                + findings("Notes", review.notes())
                + claims(review);
    }

    /**
     * Who read it, and how much they were given.
     *
     * <p>A review is only as good as its reader and its input, and until this line the report named
     * neither. Reviews written before it was recorded say nothing rather than guessing.
     */
    private String byWhom(Review review) {
        if (review.getReviewer() == null || review.getReviewer().isBlank()) {
            return "";
        }
        return "<p class=\"muted\">Reviewed by " + Html.escape(review.getReviewer())
                + (review.getScope() == null ? "" : ", from " + Html.escape(review.getScope()))
                + ".</p>";
    }

    /**
     * What the cheap pass made of each claimed feature, before the review itself ran.
     *
     * <p>Shown even when every claim held up. "Each of these was looked for and found" and "nobody
     * checked" are different states, and a report that shows only findings cannot tell them apart —
     * which is the same reason an unavailable reviewer is reported rather than read as clean.
     */
    private String claims(Review review) {
        List<FeatureCheck> checks = review.getFeatureChecks();
        if (checks == null || checks.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>Feature claims, checked one by one</h2>"
                + "<p class=\"muted\">A smaller model read the code for each feature this project "
                + "says it has, before the review above.</p><div class=\"card\"><ul>");
        for (FeatureCheck check : checks) {
            out.append("<li><strong>").append(Html.escape(check.getFeature())).append("</strong> — ")
                    .append(verdict(check));
            if (check.getWhy() != null && !check.getWhy().isBlank()) {
                out.append(" <span class=\"muted\">").append(Html.escape(check.getWhy())).append("</span>");
            }
            out.append("</li>");
        }
        return out.append("</ul></div>").toString();
    }

    private String verdict(FeatureCheck check) {
        return switch (check.getVerdict() == null ? "" : check.getVerdict()) {
            case FeatureCheck.HOLDS -> "found in the code";
            case FeatureCheck.UNPROVEN -> "<strong>could not be found</strong>";
            default -> "not checked";
        };
    }

    private String findings(String heading, List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<div class=\"card\"><strong>").append(heading).append("</strong><ul>");
        for (Finding finding : findings) {
            out.append("<li>");
            if (finding.getFile() != null && !finding.getFile().isBlank()) {
                out.append("<code>").append(Html.escape(finding.getFile())).append("</code> — ");
            }
            out.append(Html.escape(finding.getSummary()));
            if (finding.getDetail() != null && !finding.getDetail().isBlank()) {
                out.append("<br><span class=\"muted\">").append(Html.escape(finding.getDetail())).append("</span>");
            }
            out.append("</li>");
        }
        return out.append("</ul></div>").toString();
    }
}
