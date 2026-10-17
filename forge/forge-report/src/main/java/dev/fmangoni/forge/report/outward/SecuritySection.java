package dev.fmangoni.forge.report.outward;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The security check's results on the report: what ran, what did not, and what it found.
 *
 * <p>Layers are listed before findings, including the ones that did not run. "No findings" from a
 * scanner that is not installed and "no findings" from one that looked are different statements,
 * and a section that only listed findings would print them identically.
 */
@UtilityClass
public class SecuritySection {

    public String render(SecurityReport report) {
        if (report == null) {
            return "";
        }
        List<SecurityFinding> findings = report.findings();
        List<SecurityFinding> forRuling = report.forRuling();
        StringBuilder out = new StringBuilder("<h2 id=\"security\">Security</h2><div class=\"card\">");
        out.append("<p>").append(Html.escape(summary(report))).append(" <span class=\"muted\">Checked ")
                .append(Html.escape(shortSha(report.getCommit()))).append(report.getCheckedAt() == null ? ""
                        : " at " + Html.escape(report.getCheckedAt().toString())).append(".</span></p>");

        out.append("<table><tr><th>Check</th><th>Result</th><th>Detail</th></tr>");
        for (LayerResult layer : report.getLayers()) {
            out.append("<tr><td>").append(Html.escape(layer.layer())).append("</td><td>")
                    .append(Html.escape(statusText(layer))).append("</td><td class=\"muted\">")
                    .append(Html.escape(layer.detail())).append("</td></tr>");
        }
        out.append("</table>");

        if (!findings.isEmpty()) {
            out.append("<table><tr><th>Severity</th><th>Finding</th><th>Where</th><th>Evidence</th></tr>");
            for (SecurityFinding finding : findings) {
                out.append("<tr><td>").append(Html.escape(finding.severity()))
                        .append(forRuling.contains(finding) ? " <strong>— needs your decision</strong>" : "")
                        .append("</td><td>").append(Html.escape(finding.title()))
                        .append(" <span class=\"muted\">(").append(Html.escape(finding.layer())).append(")</span>")
                        .append("</td><td><code>").append(Html.escape(finding.location()))
                        .append("</code></td><td>").append(Html.escape(finding.evidence())).append("</td></tr>");
            }
            out.append("</table>");
        }
        return out.append("</div>").toString();
    }

    private String summary(SecurityReport report) {
        Map<String, Integer> counts = report.counts();
        if (counts.isEmpty()) {
            long ran = report.getLayers().stream().filter(l -> LayerResult.RAN.equals(l.status())).count();
            return "Nothing found by the " + ran + " of " + report.getLayers().size() + " checks that ran.";
        }
        return "Found: " + counts.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", ")) + ".";
    }

    private String statusText(LayerResult layer) {
        return switch (layer.status()) {
            case LayerResult.RAN -> layer.findings().isEmpty() ? "ran — nothing found" : "ran — " + layer.findings().size() + " found";
            case LayerResult.NOT_INSTALLED -> "not installed";
            case LayerResult.SKIPPED -> "skipped";
            default -> "did not complete";
        };
    }

    private String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
