package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.report.review.Notes;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * The clips, to watch. No controls.
 *
 * <p>Split from the form that rules on them because they are two different acts and the page had
 * been treating them as one. Watching is why this section is near the top: it is the only part of
 * the report that answers "is it any good" rather than "did it build", and a person opening the
 * page wants to see the thing working, not to be handed a form.
 *
 * <p>Ruling on features happens once, in the review form at the bottom, alongside everything else
 * being submitted. Radio buttons up here meant a second form on the page and a second thing to
 * remember to press — and pressing the first one started a session, which then refused the second.
 */
@UtilityClass
public class FeatureGallery {

    public String render(List<Feature> proposed, List<Feature> ideas, List<Decision> rulings,
                         Path repo, Path evidenceDir, Path runRoot) {
        List<Feature> listed = Features.listed(proposed, ideas, rulings);
        if (listed.isEmpty()) {
            return "";
        }
        Map<String, Decision> byId = Features.byId(rulings);

        StringBuilder out = new StringBuilder("<h2 id=\"features\">What it does</h2>");
        out.append("<p class=\"muted\">")
                .append(Html.escape(summary(listed, byId, repo)))
                .append(" Ruling on these is at the bottom, with the rest of the review.</p>");

        for (Feature feature : listed) {
            out.append(card(feature, byId.get(feature.id()), repo, evidenceDir, runRoot));
        }
        return out.toString();
    }

    /** The one line worth reading before deciding whether to scroll. */
    private String summary(List<Feature> listed, Map<String, Decision> byId, Path repo) {
        long filmed = listed.stream()
                .filter(feature -> Features.takeOf(repo, feature).usable()).count();
        long owed = listed.stream()
                .filter(feature -> Features.approved(byId.get(feature.id())))
                .filter(feature -> !Features.takeOf(repo, feature).usable()).count();
        long undecided = listed.stream()
                .filter(feature -> unanswered(byId.get(feature.id()))).count();

        StringBuilder out = new StringBuilder(listed.size() + " feature(s): " + filmed
                + " with a recording");
        if (owed > 0) {
            out.append(", ").append(owed).append(" approved and still to be filmed");
        }
        if (undecided > 0) {
            out.append(", ").append(undecided).append(" you have not ruled on");
        }
        return out.append(".").toString();
    }

    private String card(Feature feature, Decision ruling, Path repo, Path evidenceDir,
                        Path runRoot) {
        return "<div class=\"decide-item feature-item\">\n"
                + "  <div class=\"decide-text\">" + Html.escape(feature.name()) + "</div>\n"
                + demo(feature)
                + recording(feature, ruling, repo, evidenceDir, runRoot)
                + Notes.thread(ruling)
                + "</div>\n";
    }

    private String demo(Feature feature) {
        return feature.demo().isBlank() ? ""
                : "  <div class=\"decide-src\">" + Html.escape(feature.demo()) + "</div>\n";
    }

    /**
     * The clip, and how much to trust it.
     *
     * <p>Three states, kept distinct on purpose. A recording in the working tree was produced by
     * the run that just happened, because the gate deletes these before it starts and only a
     * command it ran can put one back. A recording found only in evidence is from an earlier
     * session and the current build did not manage to film that feature — showing it unlabelled
     * would be the report lying in the one place it exists to tell the truth. And nothing at all is
     * either work the next session owes, or a feature nobody has asked to see yet.
     */
    private String recording(Feature feature, Decision ruling, Path repo, Path evidenceDir,
                             Path runRoot) {
        RecordingTake take = Features.takeOf(repo, feature);
        if (take.usable()) {
            return player(feature, take.file(), runRoot)
                    + note("", take.clip().describe() + " · recorded by the last gate run");
        }

        Optional<Path> archived = Features.archivedRecordingOf(evidenceDir, feature);
        if (archived.isPresent()) {
            return player(feature, archived.get(), runRoot)
                    + note(" feature-owed", "From an earlier session. The last build did not "
                    + "produce this one: " + take.problem() + ".");
        }
        if (Features.approved(ruling)) {
            return note(" feature-owed", "No recording yet — the next session owes one.");
        }
        if (Features.declined(ruling)) {
            return note("", "You said this one does not need a clip.");
        }
        return note("", "Not ruled on yet. Approve it below to have it filmed.");
    }

    private String player(Feature feature, Path clip, Path runRoot) {
        String href = runRoot.relativize(clip).toString().replace('\\', '/');
        if (href.endsWith(".gif")) {
            return "  <img src=\"" + href + "\" alt=\"" + Html.escape(feature.name()) + "\">\n";
        }
        return "  <video src=\"" + href + "\" controls preload=\"metadata\"></video>\n";
    }

    private String note(String extraClass, String text) {
        return "  <div class=\"decide-src" + extraClass + "\">" + Html.escape(text) + "</div>\n";
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
