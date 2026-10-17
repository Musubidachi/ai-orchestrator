package dev.fmangoni.forge.report.outward;

import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.report.shell.Html;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Every deploy of the project, newest first, with where it went and how it went. */
@UtilityClass
public class DeploymentsSection {

    public String render(List<Deployment> history) {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2 id=\"deployments\">Deployments</h2><div class=\"card\"><table>"
                + "<tr><th>#</th><th>When</th><th>Commit</th><th>Result</th><th>Where</th></tr>");
        for (int i = history.size() - 1; i >= 0; i--) {
            Deployment d = history.get(i);
            String url = d.url() == null || d.url().isBlank() ? ""
                    : "<a href=\"" + Html.escape(d.url()) + "\" target=\"_blank\" rel=\"noopener\">"
                            + Html.escape(d.url()) + "</a>";
            out.append("<tr><td>").append(d.number()).append("</td><td>")
                    .append(Html.escape(d.finishedAt() == null ? "" : d.finishedAt().toString()))
                    .append("</td><td><code>").append(Html.escape(shortSha(d.commit()))).append("</code>")
                    .append(d.dirty() ? " <span class=\"muted\">(with uncommitted changes)</span>" : "")
                    .append("</td><td>").append(Html.escape(d.outcome()))
                    .append(d.detail() == null || d.detail().isBlank() ? ""
                            : " <span class=\"muted\">— " + Html.escape(d.detail()) + "</span>")
                    .append("</td><td>").append(url).append("</td></tr>");
        }
        return out.append("</table><p class=\"muted\">Deploys run only when Deploy is pressed on an accepted "
                + "project, with the CLI the spec names and the account it is logged into.</p></div>").toString();
    }

    private String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
