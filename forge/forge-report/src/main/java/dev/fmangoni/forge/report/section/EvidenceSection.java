package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.evidence.EvidenceGroup;
import dev.fmangoni.forge.report.evidence.EvidenceGroups;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.state.RunState;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The newest artefacts, with the earlier ones listed beneath.
 *
 * <p>Showing every session inline would be a scroll of near-identical screenshots. What a reader
 * wants is the current state, and a way back through the history when it looks wrong.
 */
public final class EvidenceSection {

    private final RunPaths paths;
    private final RunState state;

    private EvidenceSection(RunPaths paths, RunState state) {
        this.paths = paths;
        this.state = state;
    }

    public static String render(RunPaths paths, RunState state) {
        return new EvidenceSection(paths, state).render();
    }

    private String render() {
        List<EvidenceGroup> groups = EvidenceGroups.of(paths.evidence());
        if (groups.isEmpty()) {
            return "";
        }
        EvidenceGroup newest = groups.getLast();
        List<Path> shown = notFeatureRecordings(newest.files());

        StringBuilder out = new StringBuilder("<h2>Evidence</h2>");
        if (!newest.label().isBlank()) {
            out.append("<p class=\"muted\">From ")
                    .append(Html.escape(newest.label().replace("session-", "session ")))
                    .append(", the most recent run.");
            if (shown.size() < newest.files().size()) {
                out.append(" The feature recordings are collected here too, and played above rather than twice.");
            }
            out.append("</p>");
        }
        for (Path file : shown) {
            out.append(item(relativeTo(file)));
        }
        return out + earlier(groups);
    }

    /**
     * Everything except the clips the features section already plays.
     *
     * <p>The gate copies each feature's recording into the run's evidence, which is what gives the
     * report something to show when a later session fails to film one — and it also put every clip
     * on the page twice, once under its feature and again in this list. Collected, not listed: the
     * archive is for the report to fall back on, not a second gallery.
     */
    private List<Path> notFeatureRecordings(List<Path> files) {
        Set<String> owned = new HashSet<>();
        for (Feature feature : Features.listed(Feature.of(state.getPlan()), DecisionStore.load(paths.featuresFile()))) {
            for (String candidate : feature.acceptablePaths()) {
                owned.add(candidate.substring(candidate.lastIndexOf('/') + 1));
            }
        }
        return files.stream().filter(file -> !owned.contains(file.getFileName().toString())).toList();
    }

    /** Every set before the newest, as links — there to be opened, not scrolled past. */
    private String earlier(List<EvidenceGroup> groups) {
        if (groups.size() < 2) {
            return "";
        }
        StringBuilder out = new StringBuilder(
                "<details><summary>Earlier runs (" + (groups.size() - 1) + ")</summary><ul>");
        for (EvidenceGroup group : groups.subList(0, groups.size() - 1).reversed()) {
            for (Path file : notFeatureRecordings(group.files())) {
                String relative = relativeTo(file);
                out.append("<li><a href=\"").append(relative).append("\">")
                        .append(Html.escape(relative)).append("</a></li>");
            }
        }
        return out.append("</ul></details>").toString();
    }

    private String relativeTo(Path file) {
        return paths.root().relativize(file).toString().replace('\\', '/');
    }

    /**
     * Renders a piece of evidence in the form it is worth looking at.
     *
     * <p>A recording is played inline rather than linked. The point of asking for one is that a
     * person can watch the program work without building it, and a download link to a .webm is
     * enough friction that nobody ever does.
     */
    private static String item(String relative) {
        String caption = "<br><span class=\"muted\">" + Html.escape(relative) + "</span></p>";
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm") || lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return "<p><video src=\"" + relative + "\" controls preload=\"metadata\" "
                    + "style=\"max-width:100%\"></video>" + caption;
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")) {
            return "<p><img src=\"" + relative + "\" alt=\"\">" + caption;
        }
        return "<p><a href=\"" + relative + "\">" + Html.escape(relative) + "</a></p>";
    }
}
