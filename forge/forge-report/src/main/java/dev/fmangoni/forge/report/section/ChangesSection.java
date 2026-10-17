package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * What the most recent session actually changed.
 *
 * <p>A report you read repeatedly is mostly unchanged from the last time you read it, and the
 * question on the second reading is not "what is this project" but "what moved since I last
 * looked". Without this the only way to answer that was to open a terminal in the run's repository.
 *
 * <p>Measured from {@code preTurnCommit}, which the pipeline records before handing a turn to an
 * agent — so the range is exactly one session's work, whatever the agent did inside it.
 */
@UtilityClass
public class ChangesSection {

    public String render(RunPaths paths, RunState state) {
        String since = state.getPreTurnCommit();
        Git git = new Git(paths.repo(), paths.gitLog());
        String stat = git.diffStat(since, "HEAD");
        List<String> commits = git.subjects(since, "HEAD");
        if (stat.isBlank() && commits.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>What changed in the last session</h2>");
        if (!commits.isEmpty()) {
            out.append("<div class=\"card\"><ul>");
            for (String subject : commits) {
                out.append("<li>").append(Html.escape(subject)).append("</li>");
            }
            out.append("</ul></div>");
        }
        if (!stat.isBlank()) {
            out.append("<pre>").append(Html.escape(stat.strip())).append("</pre>");
        }
        return out.toString();
    }
}
