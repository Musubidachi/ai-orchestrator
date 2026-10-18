package dev.fmangoni.forge.brief.repair;

import dev.fmangoni.forge.brief.TimeContract;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The prompt when the person the work is for has looked at it and said it is not right.
 *
 * <p>Separate from the review brief because the authority is different. A reviewing model has read
 * the diff and may be wrong about intent; this is the author of the spec saying the finished thing
 * does not match what they wanted. Where the two disagree, this wins — and where this contradicts
 * the spec, the spec is what is out of date.
 */
@UtilityClass
public class FeedbackBrief {

    /**
     * Turns a reviewed list into the text of the reader's request.
     *
     * <p>Each item is quoted in the model's own words before the reader's response, because the
     * reader was ruling on a specific claim the model made. Stripping that context down to the
     * comment alone would leave "this needs a real test" attached to nothing.
     *
     * <p>Takes the whole list, not only what was sent back. A note left on an item the reader was
     * content with is still something they said about the work — "fine, but it should be written
     * down somewhere" is useful, and used to be discarded because the radio button said
     * <em>Fine as it is</em>. It is kept separate from the requests so the difference between
     * "change this" and "while you are in there" survives the trip.
     */
    public String itemised(List<Decision> decisions) {
        return itemised(decisions, List.of());
    }

    /**
     * The whole of one sitting: the review items, and what was said about the features.
     *
     * <p>Features used to be left out entirely. A request typed beside one — "if the user loses
     * a batch fails, offer an ad to buy more credit" — was saved, shown on the report, and never
     * put in front of an agent: the session it started was told to film the feature and nothing
     * else. Everything a person writes on the page reaches the next turn now, labelled by what it
     * is attached to.
     *
     * @return blank when there is nothing to say, so a caller can tell a sitting with no words in it
     *         from one with instructions
     */
    public String itemised(List<Decision> decisions, List<Decision> features) {
        return itemised(decisions, features, Set.of());
    }

    /**
     * @param toBuild ids of approved features the project does not have yet — ideas taken up and
     *                features asked for by hand. Told to build those, not merely to film them: an
     *                agent asked to record a feature that does not exist will record whatever
     *                screen is nearest.
     */
    public String itemised(List<Decision> decisions, List<Decision> features,
                           Set<String> toBuild) {
        return itemised(decisions, features, toBuild, Map.of());
    }

    /**
     * @param reviewerDetail what the reviewer wrote about each blocking finding, keyed by the text
     *                       the form showed it as. The form shows one line; the reason the line is
     *                       true is here, and an agent sent back with the line alone was guessing.
     */
    public String itemised(List<Decision> decisions, List<Decision> features,
                           Set<String> toBuild, Map<String, String> reviewerDetail) {
        List<Decision> sentBack = decisions.stream()
                .filter(Decision::wantsChanges).toList();
        List<Decision> noted = decisions.stream()
                .filter(d -> !d.wantsChanges() && d.hasComments()).toList();
        List<Decision> toChange = features.stream()
                .filter(Features::changes).toList();
        List<Decision> building = features.stream()
                .filter(f -> toBuild.contains(f.getItemId())
                        && Features.approved(f))
                .toList();
        // Only what no session has been given: a remark already delivered is not news, and sent
        // again it reads as a request still outstanding.
        List<Decision> remarked = features.stream()
                .filter(f -> !Features.changes(f) && f.hasUnheardComments())
                .filter(f -> !toBuild.contains(f.getItemId()))
                .toList();

        StringBuilder out = new StringBuilder();
        if (!sentBack.isEmpty()) {
            // Not "what you reported": most of what gets sent back is a reviewer's finding, and
            // telling the agent it wrote the thing it is being asked to fix misreads the list.
            out.append("They went through the outstanding items and sent these back:\n");
            for (Decision decision : sentBack) {
                request(out, decision.getText(), decision);
                String detail = reviewerDetail.get(decision.getText());
                if (detail != null && !detail.isBlank()) {
                    out.append("  What the reviewer found: ").append(detail).append('\n');
                }
            }
        }

        if (!building.isEmpty()) {
            out.append(out.isEmpty() ? "" : "\n")
                    .append("They want these new features built. None of them exists yet. Build ")
                    .append("each one, list it under `features` in your handoff, and record it the ")
                    .append("way AGENTS.md describes — the gate will not pass the project until ")
                    .append("each has a recording of itself working:\n");
            for (Decision feature : building) {
                request(out, "New feature: " + feature.getText(), feature);
            }
        }

        if (!toChange.isEmpty()) {
            out.append(out.isEmpty() ? "" : "\n")
                    .append("They want these features changed. Make the change, then make sure ")
                    .append("the feature's recording shows it working the new way — the old clip ")
                    .append("is no longer a recording of this feature:\n");
            for (Decision feature : toChange) {
                request(out, "Feature: " + feature.getText(), feature);
            }
        }

        if (!noted.isEmpty() || !remarked.isEmpty()) {
            out.append(out.isEmpty() ? "" : "\n")
                    .append("They were content with these, but left a remark on each. Do not treat ")
                    .append("them as work to be done unless the remark asks for something — they ")
                    .append("are context for the judgement you are being asked to respect. If a ")
                    .append("remark does ask for something, it is a request like any other:\n");
            for (Decision decision : noted) {
                out.append("\n- ").append(decision.getText()).append('\n')
                        .append("  They said: ").append(said(decision)).append('\n');
            }
            for (Decision feature : remarked) {
                out.append("\n- Feature: ").append(feature.getText()).append('\n')
                        .append("  They said: ").append(said(feature)).append('\n');
            }
        }
        return out.toString();
    }

    private void request(StringBuilder out, String what, Decision decision) {
        out.append("\n- ").append(what).append('\n');
        String said = said(decision);
        if (said.isBlank()) {
            out.append("  They did not say more; deal with it as stated.\n");
        } else {
            out.append("  They said: ").append(said).append('\n');
        }
    }

    /**
     * Everything said about one item, oldest first.
     *
     * <p>All of it rather than only the latest: notes accumulate across sittings, and a remark
     * from before a repair round is often the one that explains the remark after it.
     */
    private String said(Decision decision) {
        if (!decision.hasComments()) {
            return decision.getComment() == null ? "" : decision.getComment().strip();
        }
        return decision.getComments().stream()
                .map(Comment::text)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n  Then: "));
    }

    public String of(String feedback, Plan previous, Duration cap) {
        return """
                The person this was built for has reviewed it and asked for changes. Their words:

                ------------------------------------------------------------
                %s
                ------------------------------------------------------------

                This is not a reviewing model's opinion that you may push back on. It is the author                 of the spec telling you the finished work does not match what they wanted, and they                 have seen it running. Take it at face value.

                Two things follow from that:

                - If their request contradicts SPEC.md, the spec is what is out of date. Do what                 they asked and note the discrepancy in your handoff so the spec can be corrected.
                - The acceptance commands still have to pass afterwards. If satisfying the request                 genuinely requires changing a check, say so plainly rather than weakening it                 quietly — that is the one thing you must not do on your own authority.

                %s%s""".formatted(feedback, TimeContract.resuming(previous), TimeContract.of(cap));
    }
}
