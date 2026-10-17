package dev.fmangoni.forge.jobs.submit;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.jobs.queue.PipelineJobs;
import dev.fmangoni.forge.jobs.queue.StartRules;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.jobs.work.ReportRefresh;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What a person submits from a report: saving it, and handing it to {@link SubmissionOutcome}.
 *
 * <p>Moved out of {@code JobRunner}, which had grown to 1,100 lines doing two jobs: deciding what
 * runs, and handling the review form. The second had become most of the class and most of its bugs —
 * notes discarded on a refusal, features submitted separately and blocking the review, verdicts
 * outliving their claims, feedback left mid-session and never delivered — and it is easier to reason
 * about the rules for a submission when they are not interleaved with the rules for a queue.
 *
 * <p>Not synchronized itself. {@code JobRunner} holds its own lock around every call, so a
 * submission and a start still cannot interleave.
 */
public final class Submissions {

    private final Projects projects;
    private final JobPool pool;
    private final SubmissionOutcome outcome;

    public Submissions(Projects projects, JobPool pool, StartRules rules, PipelineJobs pipeline) {
        this.projects = projects;
        this.pool = pool;
        this.outcome = new SubmissionOutcome(pool, rules, pipeline);
    }

    /**
     * Records what you decided about each outstanding item, and does whatever follows from it.
     *
     * <p>One submission, two outcomes: if everything stands, the project is accepted; if anything
     * needs work, those items and your reasons become the next session's brief and it starts. They
     * are the same act — going through the list — rather than two buttons, because a separate
     * Accept beside the list is just a way of not reading it.
     *
     * <p>Refused before a cross-review has run. A project read by nothing except the model that
     * wrote it has not been checked by anyone, and accepting it would record a judgement about
     * work no second opinion has ever seen.
     */
    public String decide(String id, List<Decision> decisions, List<Decision> features) {
        Project project = projects.find(id).orElse(null);
        if (project == null) {
            return Projects.unknown(id);
        }
        RunPaths paths = project.paths();
        RunState state = RunStore.load(paths.stateFile()).orElse(null);
        if (state == null) {
            return "it has not run yet";
        }
        if ((decisions == null || decisions.isEmpty()) && (features == null || features.isEmpty())) {
            return "nothing was submitted";
        }

        // Everything is written before anything can refuse. What you typed is yours, and the
        // refusals are about whether Forge may act on it, not about whether it is worth keeping.
        // Twice now that has been the wrong way round: first for a project whose cross-review was
        // rate-limited, and then for one where a session had started between the reading and the
        // submitting, so the review went to a page that answered "still running".
        //
        // Realigned first: what is on disk may be keyed by the position an item held in a list the
        // agent has since rewritten, and merging onto a stale key would file this sitting's note
        // under whichever claim now sits at that index.
        List<ReviewItem> items = itemsOf(paths, state);
        List<Decision> stored = Decisions.realign(items, DecisionStore.load(paths.decisionsFile()));
        List<Decision> merged = Decisions.merge(stored, settledOrAsBefore(decisions, stored));
        DecisionStore.save(paths.decisionsFile(), merged);
        List<Decision> rulings = Decisions.merge(DecisionStore.load(paths.featuresFile()), named(features));
        DecisionStore.save(paths.featuresFile(), rulings);

        long unruled = unruled(decisions);
        if (unruled > 0) {
            // Nothing is decided by an item left blank. It used to be: the form pre-ticked "Fine as
            // it is", so leaving an item alone accepted it — including a blocking defect that had
            // come back for the third time. Notes typed are kept either way.
            ReportRefresh.write(paths, state);
            return kept(decisions, features) + unruled + (unruled == 1 ? " item still needs" : " items still need")
                    + " a ruling — pick Fine as it is or Needs work for each, then submit again";
        }

        String result = outcome.act(new Submission(id, paths, state, items, merged, decisions, rulings,
                unfilmed(paths, state, rulings), kept(decisions, features)));
        ReviewHistory.append(paths.reviewHistoryFile(), sitting(decisions, features, result));
        // Rewritten so the page a submission reloads into shows what was just saved. Without this
        // the reload served the report from before the click: the note was on disk and nowhere on
        // screen, which reads exactly like a submission that did nothing.
        ReportRefresh.write(paths, state);
        return result;
    }

    /**
     * Rules on one feature, from the dashboard, without the rest of the form.
     *
     * <p>For a project stopped on a clip, where the one thing being asked is about that clip: whether
     * it is still wanted, or is the same feature as one that has been filmed. Going through the whole
     * form for that meant ruling on every finding first, and the feature to answer was folded away
     * under "all ruled on", beside nine that were fine.
     *
     * <p>Saves the ruling and nothing else: it never starts a session, so a decline cannot send
     * an agent anywhere. Allowed whatever the project is doing, as the form's feature rulings are —
     * the gate reads the file afresh each time.
     *
     * @param sameAs the filmed feature this one is the same as, recorded beside the decline so
     *               the history says why, or blank
     */
    public String ruleFeature(String id, String itemId, String verdict, String sameAs) {
        return ruleFeature(id, itemId, verdict, sameAs, "");
    }

    /**
     * @param note what the person wants changed about it, or blank. Beside "still wanted" it is a
     *             request, and it goes to the next session as one, parked project or not: saved
     *             only on the feature, it would be shown on the report and read by nobody.
     */
    public String ruleFeature(String id, String itemId, String verdict, String sameAs, String note) {
        Project project = projects.find(id).orElse(null);
        if (project == null) {
            return Projects.unknown(id);
        }
        RunPaths paths = project.paths();
        RunState state = RunStore.load(paths.stateFile()).orElse(null);
        if (state == null) {
            return "it has not run yet";
        }
        if (!Features.APPROVED.equals(verdict) && !Features.DECLINED.equals(verdict)) {
            return "a feature is either still wanted or not worth a clip";
        }
        String said = note == null ? "" : note.strip();
        boolean running = pool.isRunning(id);
        if (running && Features.APPROVED.equals(verdict) && !said.isEmpty()) {
            // Its state belongs to the session until it ends, so the request has nowhere safe to go.
            return "it is being worked on; add the note once that stops";
        }
        List<Decision> stored = DecisionStore.load(paths.featuresFile());
        Feature feature = Features.listed(Feature.of(state.getPlan()), Feature.ideas(state.getPlan()), stored)
                .stream()
                .filter(listed -> listed.id().equals(itemId))
                .findFirst().orElse(null);
        if (feature == null) {
            return "there is no feature " + itemId + " on " + id;
        }
        String twin = sameAs == null ? "" : sameAs.strip();
        if (!twin.isEmpty() && Feature.of(state.getPlan()).stream().noneMatch(f -> f.name().equals(twin))) {
            return "the agent lists no feature called " + twin;
        }

        String why = !twin.isEmpty() ? "Same feature as \"" + twin + "\", which it was renamed to." : said;
        List<Decision> rulings = Decisions.merge(stored,
                List.of(Decision.of(feature.id(), feature.name(), verdict, why)));
        if (Features.DECLINED.equals(verdict)) {
            DecisionStore.save(paths.featuresFile(), rulings);
            ReportRefresh.write(paths, state);
            return "\"" + feature.name() + "\" no longer needs a clip"
                    + (twin.isEmpty() ? "." : ": it is \"" + twin + "\".")
                    + (state.getPhase() == Phase.PARKED && state.getLastFailure() != null
                            ? " Check Again runs the gate without spending a session." : "");
        }

        if (!said.isEmpty()) {
            String request = "About the feature \"" + feature.name() + "\", which I still want: " + said
                    + "\nIt still needs its recording, at " + feature.recordingPath(".webm") + ".";
            String waiting = state.getUserFeedback();
            state.setUserFeedback(waiting == null || waiting.isBlank() ? request : waiting + "\n\n" + request);
            // Handed over with the brief, so nothing later delivers the same note a second time.
            rulings.stream().filter(ruling -> feature.id().equals(ruling.getItemId()))
                    .forEach(ruling -> ruling.setHeardAt(Instant.now()));
        }
        DecisionStore.save(paths.featuresFile(), rulings);
        if (!running) {
            List<String> kept = new ArrayList<>(state.getKeptClips() == null ? List.of() : state.getKeptClips());
            if (!kept.contains(feature.id())) {
                kept.add(feature.id());
            }
            state.setKeptClips(kept);
            RunStore.save(paths.stateFile(), state);
        }
        ReportRefresh.write(paths, state);
        return "\"" + feature.name() + "\" is still wanted"
                + (said.isEmpty() ? ", so it still needs a clip." : ", with your note.")
                + " Run Another Session " + (said.isEmpty() ? "gives the agent another go at it."
                        : "sends the note to the agent, and the agent builds and films it.");
    }

    /** Review items submitted with neither verdict chosen. The placeholder row for an empty list is not one. */
    private static long unruled(List<Decision> decisions) {
        return decisions == null ? 0 : decisions.stream()
                .filter(decision -> !"none".equals(decision.getItemId()))
                .filter(decision -> !"ok".equalsIgnoreCase(decision.getVerdict())
                        && !decision.wantsChanges())
                .count();
    }

    /**
     * This sitting's rulings, with any left blank keeping the verdict already on record.
     *
     * <p>So a blank does not overwrite a "needs work" still waiting to be delivered, and a note typed
     * beside a blank is still kept.
     */
    private static List<Decision> settledOrAsBefore(List<Decision> decisions, List<Decision> stored) {
        if (decisions == null) {
            return List.of();
        }
        List<Decision> out = new ArrayList<>();
        for (Decision decision : decisions) {
            boolean ruled = "ok".equalsIgnoreCase(decision.getVerdict()) || decision.wantsChanges();
            out.add(ruled ? decision : Decision.of(decision.getItemId(), decision.getText(),
                    verdictOnRecord(stored, decision.getItemId()), decision.getComment()));
        }
        return out;
    }

    private static String verdictOnRecord(List<Decision> stored, String itemId) {
        return stored.stream()
                .filter(previous -> itemId != null && itemId.equals(previous.getItemId()))
                .map(Decision::getVerdict).findFirst().orElse("ok");
    }

    /** One submission, as the history keeps it: what was ruled, as it was ruled, and what followed. */
    private static Sitting sitting(List<Decision> decisions, List<Decision> features, String result) {
        List<Decision> rulings = new ArrayList<>();
        for (Decision decision : decisions == null ? List.<Decision>of() : decisions) {
            if (!"none".equals(decision.getItemId())) {
                rulings.add(Decision.of(decision.getItemId(), decision.getText(), decision.getVerdict(),
                        decision.getComment() == null ? "" : decision.getComment()));
            }
        }
        List<Decision> featureRulings = new ArrayList<>();
        for (Decision feature : features == null ? List.<Decision>of() : features) {
            featureRulings.add(Decision.of(feature.getItemId(), feature.getText(), feature.getVerdict(),
                    feature.getComment() == null ? "" : feature.getComment()));
        }
        return new Sitting(Instant.now(), result, rulings, featureRulings);
    }

    /**
     * Acts on feedback that arrived while this project's session was running, now that it is not.
     *
     * <p>Only on a finished project. One that stopped short — suspended on quota, parked — keeps its
     * marker, and the next session to finish picks it up.
     *
     * <p>Never accepts. The submission was a judgement about the work as it stood before this
     * session, and whatever the session has done since has not been read by anyone: an old "fine as
     * it is" must not sign off new code. Requests for change are still requests, so those reopen it.
     */
    public void deliverPending(String id, RunPaths paths) {
        if (!Files.exists(paths.pendingFeedbackFile())) {
            return;
        }
        RunState state = RunStore.survey(paths.stateFile()).orElse(null);
        if (state == null || state.getPhase() != Phase.DONE) {
            return;
        }
        try {
            Files.deleteIfExists(paths.pendingFeedbackFile());
        } catch (IOException e) {
            System.err.println("could not clear pending feedback for " + id + ": " + e);
            return;
        }
        List<ReviewItem> items = itemsOf(paths, state);
        List<Decision> merged = Decisions.realign(items, DecisionStore.load(paths.decisionsFile()));
        List<Decision> rulings = DecisionStore.load(paths.featuresFile());

        // No decisions of this sitting: that is what keeps the outcome from accepting.
        String result = outcome.act(new Submission(id, paths, state, items, merged, List.of(), rulings,
                unfilmed(paths, state, rulings), ""));
        System.out.println("[" + id + "] feedback left during the session: " + result);
        ReportRefresh.write(paths, state);
    }

    private static List<ReviewItem> itemsOf(RunPaths paths, RunState state) {
        return ReviewItem.of(state.getPlan(), ReviewStore.load(paths.reviewFile()),
                SecurityReport.load(paths.securityFile()));
    }

    private static List<RecordingTake> unfilmed(RunPaths paths, RunState state, List<Decision> rulings) {
        return Features.unrecorded(paths.repo(), Features.owed(Features.knownTo(state.getPlan()), rulings));
    }

    /**
     * Gives a feature you asked for yourself the id the rest of Forge will look it up by.
     *
     * <p>Derived here rather than in the browser, so the name-to-filename rule lives in exactly one
     * place. The page would have to reimplement the slug to send an id, and the first time the two
     * disagreed the gate would sit waiting for a recording at a path nobody was writing to.
     */
    private static List<Decision> named(List<Decision> rulings) {
        List<Decision> out = new ArrayList<>();
        for (Decision ruling : rulings) {
            if (ruling.getItemId() == null || ruling.getItemId().isBlank()) {
                ruling.setItemId(new Feature(ruling.getText(), "").id());
            }
            out.add(ruling);
        }
        return out;
    }

    /**
     * Says what was kept, so a refusal does not read as though the submission vanished.
     *
     * <p>Empty when nothing was typed: "0 note(s) saved" beside a refusal is noise, and the
     * refusal is the only thing worth reading in that case.
     */
    private static String kept(List<Decision> decisions, List<Decision> features) {
        long notes = Stream.of(decisions, features)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(d -> d.getComment() != null && !d.getComment().isBlank())
                .count();
        return notes == 0 ? "" : notes + (notes == 1 ? " note saved — " : " notes saved — ");
    }
}
