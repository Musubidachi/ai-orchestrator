package dev.fmangoni.forge.report.outward;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.marketing.KitProblem;
import dev.fmangoni.forge.marketing.KitStatus;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The marketing kit on the report: every piece of copy ready to paste, and what the check found.
 *
 * <p>Shown here, on the page Forge writes, rather than on the kit's own landing page: that page was
 * written by a model and is served sandboxed like everything else an agent writes. The copy buttons
 * need a page that is Forge's.
 *
 * <p>Nothing on this page publishes anything. It says so, because a section full of ad campaigns
 * beside a button is exactly where someone would reasonably wonder whether pressing it spends money.
 */
@UtilityClass
public class MarketingSection {

    public String render(RunPaths paths, String projectId) {
        KitStatus status = KitStatus.load(paths.marketingStatusFile());
        if (status == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2 id=\"marketing\">Marketing kit</h2><div class=\"card\">");
        out.append("<p class=\"muted\">Written by ").append(Html.escape(status.agent().isBlank() ? "nobody" : status.agent()))
                .append(status.madeAt() == null ? "" : " at " + Html.escape(status.madeAt().toString()))
                .append(". Forge never posts, publishes, uploads a campaign or spends money — everything here is "
                        + "for you to review and use yourself.</p>");

        if ("unavailable".equals(status.outcome())) {
            return out.append("<p>").append(Html.escape(status.detail())).append("</p></div>").toString();
        }
        if (!status.problems().isEmpty()) {
            out.append("<p><strong>").append(status.problems().size())
                    .append(" problem(s) to fix before using this kit:</strong></p><ul>");
            for (KitProblem problem : status.problems()) {
                out.append("<li><code>").append(Html.escape(problem.where())).append("</code> — ")
                        .append(Html.escape(problem.what())).append("</li>");
            }
            out.append("</ul>");
        } else {
            out.append("<p>Checked: every claim cites an approved feature, nothing invented, links go to the "
                    + "project's own site, and every field is within its platform's limit.</p>");
        }

        JsonNode kit = read(paths.marketingDir().resolve("kit.json"));
        if (kit != null) {
            copyable(out, "Tagline", kit.path("tagline").asText(""));
            copyable(out, "Short description", kit.path("short_description").asText(""));
            copyable(out, "Long description", kit.path("long_description").asText(""));
            copyable(out, "Post for X", kit.path("posts").path("x").asText(""));
            copyable(out, "Reddit title", kit.path("posts").path("reddit").path("title").asText(""));
            copyable(out, "Reddit post", kit.path("posts").path("reddit").path("body").asText(""));
            copyable(out, "Show HN title", kit.path("posts").path("hacker_news").path("title").asText(""));
            copyable(out, "LinkedIn post", kit.path("posts").path("linkedin").asText(""));
            copyable(out, "Press paragraph", kit.path("press").asText(""));
        }
        String base = "/runs/" + Html.escape(projectId) + "/marketing/";
        out.append("<p>");
        link(out, paths.marketingDir().resolve("site/index.html"), base + "site/index.html", "Landing page");
        for (String file : List.of("google-search.csv", "meta.json", "reddit.json")) {
            link(out, paths.marketingDir().resolve("campaigns").resolve(file), base + "campaigns/" + file,
                    "campaigns/" + file);
        }
        return out.append("</p></div>").toString();
    }

    private void copyable(StringBuilder out, String label, String text) {
        if (text.isBlank()) {
            return;
        }
        out.append("<div class=\"kit-item\"><label>").append(Html.escape(label)).append(" <span class=\"muted\">(")
                .append(text.codePointCount(0, text.length())).append(" characters)</span></label>")
                .append("<textarea readonly rows=\"").append(Math.min(8, 2 + text.length() / 90)).append("\" style=\"width:100%\">")
                .append(Html.escape(text)).append("</textarea>")
                .append("<button type=\"button\" onclick=\"navigator.clipboard.writeText(this.previousElementSibling.value);"
                        + "this.textContent='Copied'\">Copy</button></div>");
    }

    private void link(StringBuilder out, Path file, String href, String label) {
        if (Files.isRegularFile(file)) {
            out.append("<a href=\"").append(href).append("\" target=\"_blank\" rel=\"noopener\">")
                    .append(Html.escape(label)).append("</a> &nbsp; ");
        }
    }

    private JsonNode read(Path file) {
        try {
            return Files.isRegularFile(file) ? Json.mapper().readTree(Files.readString(file)) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
