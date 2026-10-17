package dev.fmangoni.forge.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.PolishRecord;
import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.marketing.KitProblem;
import dev.fmangoni.forge.marketing.KitStatus;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.Decisions;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.feature.FeatureCheck;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The page a person actually reads.
 *
 * <p>The report had no test at all, which showed: evidence was linked rather than shown for
 * anything that was not an image, and there was no way to see what a session had changed without
 * opening a terminal in the run's repository. Both are the kind of defect that is obvious on screen
 * and invisible to every other test here.
 */
class ProjectReportTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private RunState state;

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.evidence());

        state = new RunState();
        state.setId("001");
        state.setTitle("A thing");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);
    }

    private String report(Review review) throws IOException {
        new ProjectReport(paths, state, review).write();
        return Files.readString(paths.reportFile(), StandardCharsets.UTF_8);
    }

    private void evidence(String name) throws IOException {
        Path file = paths.evidence().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not really a media file");
    }

    // ---------------------------------------------------------------- evidence

    @Test
    @DisplayName("a recording is played in the page, not offered as a download")
    void videoEvidenceIsPlayable() throws IOException {
        // The point of asking for a recording is that someone can watch the program work without
        // building it. A link to a .webm is enough friction that nobody ever does.
        evidence("race.webm");

        String html = report(null);

        assertTrue(html.contains("<video"), "expected an inline player:\n" + html);
        assertTrue(html.contains("controls"), "a player nobody can start is not a player");
        assertFalse(html.contains("<a href=\"evidence/race.webm\""),
                "the recording should play, not download");
    }

    @Test
    @DisplayName("images are still shown inline")
    void imageEvidenceIsShown() throws IOException {
        evidence("shot.png");

        assertTrue(report(null).contains("<img src=\"evidence/shot.png\""));
    }

    @Test
    @DisplayName("anything else is linked, because there is nothing sensible to render")
    void otherEvidenceIsLinked() throws IOException {
        evidence("out.csv");

        String html = report(null);

        assertTrue(html.contains("<a href=\"evidence/out.csv\""));
        assertFalse(html.contains("<video"));
    }

    // ---------------------------------------------------------------- changes

    @Test
    @DisplayName("the report says what the last session changed")
    void changesSectionListsTheSessionsWork() throws IOException {
        Git git = new Git(paths.repo(), paths.logs().resolve("git.log"));
        git.init();
        Files.writeString(paths.repo().resolve("Main.java"), "class Main {}");
        String before = git.commitAll("Baseline");

        Files.writeString(paths.repo().resolve("Main.java"), "class Main { void run() {} }");
        Files.writeString(paths.repo().resolve("Extra.java"), "class Extra {}");
        git.commitAll("Continue (session 2) (CLAUDE)");

        state.setPreTurnCommit(before);

        String html = report(null);

        assertTrue(html.contains("What changed in the last session"), html);
        assertTrue(html.contains("Continue (session 2) (CLAUDE)"), "the commit subject is missing");
        assertTrue(html.contains("Extra.java"), "the changed files are missing");
    }

    @Test
    @DisplayName("a project that has not had a session yet simply omits the section")
    void changesSectionIsOmittedWithoutHistory() throws IOException {
        String html = report(null);

        assertFalse(html.contains("What changed in the last session"));
    }

    @Test
    @DisplayName("an unresolvable commit omits the section rather than failing the report")
    void unresolvableRangeDoesNotBreakTheReport() throws IOException {
        Git git = new Git(paths.repo(), paths.logs().resolve("git.log"));
        git.init();
        Files.writeString(paths.repo().resolve("Main.java"), "class Main {}");
        git.commitAll("Baseline");

        // History can be rewritten, or a run copied between machines. A report that refuses to
        // render because of a missing commit is worse than one that quietly omits a section.
        state.setPreTurnCommit("0000000000000000000000000000000000000000");

        String html = report(null);

        assertTrue(html.contains("A thing"), "the rest of the report must still be there");
        assertFalse(html.contains("What changed in the last session"));
    }

    // ---------------------------------------------------------------- review

    @Test
    @DisplayName("a review that never ran is reported as unavailable, not as clean")
    void unavailableReviewIsHonest() throws IOException {
        String html = report(Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        assertTrue(html.contains("Unavailable"), html);
        assertFalse(html.contains("No findings."),
                "silence from a reviewer that never looked is not an absence of findings");
    }

    @Test
    @DisplayName("a rate-limited reviewer blocks accepting, not writing something down")
    void notesCanBeLeftBeforeTheCrossReview() throws IOException {
        state.setPlan(planWith("the draw branch has never been rendered"));

        String html = report(Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        // The page used to replace the whole form with "come back later", so a project whose
        // reviewer was out of quota offered nowhere to record the reading you had just done.
        // Asserted on markup the form emits, not on the class name: the stylesheet names
        // .decide-item on every page, so contains("decide-item") is true even with no form.
        assertTrue(html.contains("data-item="), "the form was replaced by a notice:\n" + html);
        assertTrue(html.contains("cannot be accepted yet"), html);
        assertTrue(html.contains("Your notes are saved either way"), html);
    }

    @Test
    @DisplayName("a project suspended on its way into review still offers somewhere to write")
    void suspendedAwaitingReviewStillOffersTheForm() throws IOException {
        // The exact state this was reported in: the gate passed, the reviewing vendor was out of
        // quota, and the project sat SUSPENDED with REVIEWING to resume into. The report showed
        // "Not accepted — reviewer unavailable: RATE_LIMITED" and offered nothing to do about it.
        state.setPhase(Phase.SUSPENDED);
        state.setResumePhase(Phase.REVIEWING);
        state.setPlan(planWith("the draw branch has never been rendered"));

        String html = report(Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        assertTrue(html.contains("data-item="), "no form on a finished project:\n" + html);
        assertTrue(html.contains("waiting on the other model's quota window"), html);
    }

    @Test
    @DisplayName("a project still building takes feedback, and says it cannot act on it yet")
    void unfinishedProjectsStillTakeFeedback() throws IOException {
        // This used to render nothing at all, on the argument that offering approval mid-build
        // invites a judgement on code the gate has not seen. The argument was wrong about which
        // half to withhold: a session running is exactly when somebody is watching the project and
        // has something to say, and the one report of this ended with a review typed into a page
        // that then refused it, because a session had started between the reading and the
        // submitting. Acting is what a running session blocks. Writing it down never is.
        state.setPhase(Phase.REPAIRING);
        state.setPlan(planWith("the draw branch has never been rendered"));

        String html = report(new Review());

        assertTrue(html.contains("data-item="), "nowhere to write:\n" + html);
        assertTrue(html.contains("A session is running"), html);
        assertTrue(html.contains("saved now"), "it must say the note is kept:\n" + html);
    }

    @Test
    @DisplayName("notes already left are shown as a thread, not refilled into the input")
    void previousNotesAreShown() throws IOException {
        state.setPlan(planWith("the draw branch has never been rendered"));
        DecisionStore.save(paths.decisionsFile(), Decisions.merge(List.of(),
                List.of(Decision.of("unproven-0", "the draw branch has never been rendered",
                        "ok", "ok for now, but it needs a real test"))));

        String html = report(new Review());

        assertTrue(html.contains("<div class=\"decide-note\">"),
                "no thread was rendered:\n" + html);
        assertTrue(html.contains("ok for now, but it needs a real test"), html);
        // Putting it back in the box read as a draft to edit, and meant one remark could exist.
        assertFalse(html.contains("value=\"ok for now"), "the note was refilled into the input");
    }

    private static Review blockingReview(String summary) {
        Finding finding = new Finding();
        finding.setSeverity("blocking");
        finding.setFile("src/Docx.java");
        finding.setSummary(summary);
        Review review = new Review();
        review.setFindings(new ArrayList<>(List.of(finding)));
        return review;
    }

    private static String idOfClaim(String claim) {
        return ReviewItem.of("unproven", ReviewItem.NOT_VERIFIED, claim).id();
    }

    @Test
    @DisplayName("a blocking finding starts with nothing ticked, so it cannot be accepted unread")
    void blockingFindingsStartUnticked() throws IOException {
        String html = report(blockingReview("text around tables comes out of order"));

        assertTrue(html.contains("text around tables comes out of order"), html);
        assertFalse(html.contains("value=\"ok\" checked"), "Fine as it is was pre-ticked:\n" + html);
    }

    @Test
    @DisplayName("an item never ruled on still starts at Fine as it is")
    void newNonBlockingItemsKeepTheirDefault() throws IOException {
        state.setPlan(planWith("the draw branch has never been rendered"));

        String html = report(new Review());

        assertTrue(html.contains("value=\"ok\" checked"), html);
    }

    @Test
    @DisplayName("an item that comes back after a submitted review has nothing ticked, and says so")
    void itemsThatComeBackStartUnticked() throws IOException {
        String claim = "the draw branch has never been rendered";
        state.setPlan(planWith(claim));
        ReviewHistory.append(paths.reviewHistoryFile(), new Sitting(Instant.parse("2026-09-27T20:00:00Z"),
                "sent back; started", List.of(Decision.of(idOfClaim(claim), claim, "changes", "render it")),
                List.of()));

        String html = report(new Review());

        assertTrue(html.contains("Came back — you ruled it Needs work on"), html);
        assertFalse(html.contains("value=\"ok\" checked"), "last time's answer was carried forward:\n" + html);
        assertFalse(html.contains("value=\"changes\" checked"), html);
    }

    @Test
    @DisplayName("every submitted review is listed, newest first, with what came of it")
    void pastReviewsAreListed() throws IOException {
        String claim = "the draw branch has never been rendered";
        state.setPlan(planWith(claim));
        ReviewHistory.append(paths.reviewHistoryFile(), new Sitting(Instant.parse("2026-09-20T10:00:00Z"),
                "sent back; started", List.of(Decision.of(idOfClaim(claim), claim, "changes", "render it")),
                List.of()));
        ReviewHistory.append(paths.reviewHistoryFile(), new Sitting(Instant.parse("2026-09-27T10:00:00Z"),
                "accepted", List.of(Decision.of(idOfClaim(claim), claim, "ok", "")), List.of()));

        String html = report(new Review());

        int table = html.indexOf("Your past reviews");
        assertTrue(table > html.indexOf("</form>"), "the history sits after the form:\n" + html);
        assertTrue(html.indexOf("accepted") < html.indexOf("sent back; started"), "newest first");
        assertTrue(html.contains("<td>Needs work</td>") && html.contains("<td>render it</td>"), html);
    }

    @Test
    @DisplayName("rulings from before the history was kept are shown, and count as having been made")
    void rulingsBeforeTheHistoryAreKept() throws IOException {
        String claim = "the draw branch has never been rendered";
        state.setPlan(planWith(claim));
        DecisionStore.save(paths.decisionsFile(), List.of(Decision.of(idOfClaim(claim), claim, "changes", "")));

        String html = report(new Review());

        assertTrue(html.contains("Came back — you last ruled it Needs work."), html);
        assertTrue(html.contains("before this history was kept"), html);
    }

    @Test
    @DisplayName("a project held for you says what it is waiting for, not that a session is running")
    void aHeldProjectSaysSo() throws IOException {
        state.setPhase(Phase.REVIEW_REPAIR);
        state.setNeedsPerson("still not fixed after 2 attempts: text around tables comes out of order");

        String html = report(blockingReview("text around tables comes out of order"));

        assertTrue(html.contains("Waiting for you: still not fixed after 2 attempts"), html);
        assertFalse(html.contains("A session is running"), html);
    }

    @Test
    @DisplayName("notes on things no longer listed sit outside the form, with nothing to submit")
    void orphanedNotesAreNotPartOfTheForm() throws IOException {
        // This broke Submit review outright. The past notes are .decide-item too, and the script
        // read every one of those on the page — including these, which have no radio and no text
        // box. Dereferencing the missing input threw, the listener died after preventDefault, and
        // the button did nothing at all: no message, no request, nothing in the report to explain
        // it. Only the one project on this machine with orphaned notes was affected, which is why
        // it survived every other test here.
        state.setPlan(planWith("the draw branch has never been rendered"));
        DecisionStore.save(paths.decisionsFile(), Decisions.merge(List.of(),
                List.of(Decision.of("unproven-0", "a claim the agent has since stopped making",
                        "ok", "worth remembering anyway"))));

        String html = report(new Review());

        int form = html.indexOf("</form>");
        int orphan = html.indexOf("decide-item decide-past");
        assertTrue(orphan > 0, "the orphaned note was dropped:\n" + html);
        assertTrue(form > 0 && orphan > form,
                "a row with no controls inside the form is a row the script cannot read");
    }

    @Test
    @DisplayName("an orphaned note survives a project with nothing left outstanding")
    void orphanedNotesSurviveAnEmptyList() throws IOException {
        // The empty-list branch renders its own short form and used to stop there, so the last
        // item leaving the list took every note ever written about it off the page.
        DecisionStore.save(paths.decisionsFile(), Decisions.merge(List.of(),
                List.of(Decision.of("unproven-0", "a claim the agent has since stopped making",
                        "ok", "worth remembering anyway"))));

        assertTrue(report(new Review()).contains("worth remembering anyway"));
    }

    // ---------------------------------------------------------------- features

    private Plan planProposing(String... names) {
        Plan plan = new Plan();
        plan.setStatus("complete");
        plan.setSummary("built it");
        for (String name : names) {
            plan.getFeatures().add(
                    new PlanFeature(name, "click the thing"));
        }
        return plan;
    }

    private void film(String name) throws IOException {
        // A real recording, because a file with the right extension no longer counts as one.
        Clips.write(paths.repo().resolve(
                new Feature(name, "").recordingPath(".webm")));
    }

    @Test
    @DisplayName("proposed features are offered for approval, with what each would show")
    void proposedFeaturesAreOfferedForApproval() throws IOException {
        state.setPlan(planProposing("Garage tuning", "Pink slip wager"));

        String html = report(new Review());

        assertTrue(html.contains("feature-garage-tuning"), html);
        assertTrue(html.contains("Pink slip wager"), html);
        assertTrue(html.contains("click the thing"), "the demo note is what says what to film");
        assertTrue(html.contains("value=\"approved\""), "no way to say yes:\n" + html);
    }

    @Test
    @DisplayName("a project proposing nothing still lets you ask for something")
    void featuresCanBeAskedForWithoutAProposal() throws IOException {
        // The section renders even with an empty list, on purpose. If it only appeared once the
        // agent had proposed something, then a project whose handoff mentioned no features would
        // be one you could never ask to see working — which hands the agent the decision this
        // whole arrangement is built to keep away from it.
        String html = report(new Review());

        assertTrue(html.contains("feature-name"), "no way to ask for one:\n" + html);
        assertTrue(html.contains("nothing proposed yet"), html);
        // No gallery, though: there is nothing to watch, and an empty heading is just furniture.
        assertFalse(html.contains("id=\"features\""), html);
    }

    @Test
    @DisplayName("the security section says which checks did not run, and a serious finding reaches the form")
    void securityReport() throws IOException {
        state.setPlan(planWith("the draw branch has never been rendered"));
        new SecurityReport("0123456789abcdef", "tree", List.of(
                LayerResult.ran("secrets", "12 file(s)", List.of(
                        new SecurityFinding("secrets", "critical",
                                "AWS access key id committed", "src/keys.ts:3", "AKIA… (20 characters, redacted)", true))),
                LayerResult.notInstalled("static analysis", "semgrep is not installed"),
                LayerResult.skipped("live probe", "no 'serve' command")))
                .save(paths.securityFile());

        String html = report(new Review());

        assertTrue(html.contains("<h2 id=\"security\">Security</h2>"), html);
        assertTrue(html.contains("not installed"), "a check that never ran must not read as clean");
        assertTrue(html.contains("needs your decision"));
        assertTrue(html.contains("data-kind=\"review\"") && html.contains("AWS access key id committed — src/keys.ts:3"),
                "the serious finding is not in the decision form:\n" + html);
        String form = html.substring(html.indexOf("data-kind=\"review\""));
        assertTrue(form.indexOf("AWS access key id committed — src/keys.ts:3")
                        < form.indexOf("the draw branch has never been rendered"),
                "security items come first: they can hurt someone other than the reader");
    }

    @Test
    @DisplayName("the marketing kit is on the report, copyable, with its problems and a plain word that nothing is published")
    void marketingKitSection() throws IOException {
        Files.createDirectories(paths.marketingDir());
        Files.writeString(paths.marketingDir().resolve("kit.json"),
                "{\"tagline\":\"Race for pink slips\",\"posts\":{\"x\":\"Bet your car <on a race>\"}}");
        JsonFile.write(paths.marketingStatusFile(), Json.mapper()
                .writeValueAsString(new KitStatus(Instant.now(), "abc",
                        "CODEX", "problems", "1 problem(s)", List.of(
                        new KitProblem("site/index.html", "invented social proof: \"5,000 players\"")))));

        String html = report(new Review());

        assertTrue(html.contains("<h2 id=\"marketing\">Marketing kit</h2>"), html);
        assertTrue(html.contains("Forge never posts, publishes, uploads a campaign or spends money"));
        assertTrue(html.contains("invented social proof"));
        assertTrue(html.contains("Bet your car &lt;on a race&gt;"), "kit text is escaped: a model wrote it");
        assertTrue(html.contains(">Copy</button>"));
    }

    @Test
    @DisplayName("a feature note no agent has seen says so, and stops saying so once one has")
    void unsentFeatureNotesAreMarked() throws IOException {
        state.setPlan(planProposing("Empty garage reset"));
        List<Decision> rulings = Decisions.merge(List.of(), List.of(
                Decision.of("feature-empty-garage-reset", "Empty garage reset",
                        Features.APPROVED, "offer a dealer ad")));
        DecisionStore.save(paths.featuresFile(), rulings);

        assertTrue(report(new Review()).contains("Not yet sent to an agent"),
                "003's note looked dealt with for a day while no agent had seen it");

        rulings.get(0).setHeardAt(Instant.now());
        DecisionStore.save(paths.featuresFile(), rulings);

        assertFalse(report(new Review()).contains("Not yet sent to an agent"));
    }

    @Test
    @DisplayName("a recording is played in the page, which is the entire point of asking for one")
    void recordingsArePlayedInline() throws IOException {
        state.setPlan(planProposing("Garage tuning"));
        DecisionStore.save(paths.featuresFile(), List.of(Decision.of(
                "feature-garage-tuning", "Garage tuning",
                Features.APPROVED, "")));
        film("Garage tuning");

        String html = report(new Review());

        assertTrue(html.contains("<video"), "a link to a .webm is enough friction that nobody "
                + "ever clicks it:\n" + html);
        assertTrue(html.contains("feature-garage-tuning.webm"), html);
        assertFalse(html.contains("No recording yet"), "it has one");
    }

    @Test
    @DisplayName("an approved feature that has not been filmed says so")
    void owedRecordingsAreVisible() throws IOException {
        state.setPlan(planProposing("Garage tuning"));
        DecisionStore.save(paths.featuresFile(), List.of(Decision.of(
                "feature-garage-tuning", "Garage tuning",
                Features.APPROVED, "")));

        String html = report(new Review());

        assertTrue(html.contains("No recording yet"), html);
        assertFalse(html.contains("<video"), "there is nothing to play");
    }

    @Test
    @DisplayName("a ruling already given comes back checked, so the page is not a form filled twice")
    void previousRulingsAreShown() throws IOException {
        state.setPlan(planProposing("Garage tuning"));
        DecisionStore.save(paths.featuresFile(), Decisions.merge(List.of(), List.of(Decision.of(
                "feature-garage-tuning", "Garage tuning",
                Features.DECLINED, "it is just a form"))));

        String html = report(new Review());

        assertTrue(html.contains("value=\"declined\" checked"), html);
        assertTrue(html.contains("it is just a form"), "the note was dropped:\n" + html);
    }

    @Test
    @DisplayName("ideas for what next are offered, and one taken up is not offered twice")
    void ideasAreOfferedOnce() throws IOException {
        state.setPlan(planProposing("Garage tuning"));
        state.getPlan().getIdeas().add(new PlanFeature(
                "Rival leaderboard", "a table of the bots you have beaten"));
        state.getPlan().getIdeas().add(new PlanFeature(
                "Night races", "the same race with headlights"));
        DecisionStore.save(paths.featuresFile(), List.of(Decision.of(
                "feature-rival-leaderboard", "Rival leaderboard",
                Features.APPROVED, "")));

        String html = report(new Review());

        assertTrue(html.contains("ideas-fold"), html);
        assertTrue(html.contains("Night races"), "an unanswered idea is missing:\n" + html);
        assertTrue(html.contains("Build it"), html);
        // Taken up, so it is a feature now: owed, shown in the gallery, ruled on there — and not
        // offered as an idea as well, which would be two sets of buttons for one decision.
        int ideasFold = html.indexOf("ideas-fold");
        assertFalse(html.substring(ideasFold).contains("data-text=\"Rival leaderboard\""),
                "an approved idea is still being offered as an idea");
        assertTrue(html.substring(0, ideasFold).contains("Rival leaderboard"),
                "an approved idea should now be listed among the features");
    }

    @Test
    @DisplayName("a feature can be sent back for changes, not only approved or declined")
    void featuresOfferNeedsChanges() throws IOException {
        state.setPlan(planProposing("Empty garage reset"));

        assertTrue(report(new Review()).contains("value=\"changes\"> Needs changes"));
    }

    @Test
    @DisplayName("the review says who read it, and how much it was shown")
    void reviewNamesItsReader() throws IOException {
        Review review = new Review();
        review.setReviewer("ANTIGRAVITY");
        review.setScope("56474 characters of diff");

        String html = report(review);

        assertTrue(html.contains("Reviewed by ANTIGRAVITY, from 56474 characters of diff"), html);
    }

    @Test
    @DisplayName("a feature's clip is played once, not again in the evidence list")
    void featureRecordingsAreNotListedTwice() throws IOException {
        // Reported. The gate copies each recording into the run's evidence — which is what gives
        // the report something to show when a later session fails to film one — and the evidence
        // section renders every .webm it finds inline. So every clip appeared twice on the page,
        // once under its feature and once again a few sections down, playing the same thing.
        state.setPlan(planProposing("Garage tuning"));
        DecisionStore.save(paths.featuresFile(), List.of(Decision.of(
                "feature-garage-tuning", "Garage tuning",
                Features.APPROVED, "")));
        film("Garage tuning");
        collected("session-3/artifacts/feature-garage-tuning.webm");
        collected("session-3/artifacts/gameplay.png");

        String html = report(new Review());

        assertEquals(1, html.split("feature-garage-tuning\\.webm", -1).length - 1,
                "the clip is on the page more than once:\n" + html);
        assertTrue(html.contains("gameplay.png"), "the rest of the evidence must still be shown");
        assertTrue(html.contains("played above rather than twice"),
                "an omission nobody can explain reads as a missing file:\n" + html);
    }

    /** A file the gate copied into this run's evidence. */
    private void collected(String relative) throws IOException {
        Path file = paths.evidence().resolve(relative);
        Files.createDirectories(file.getParent());
        Clips.write(file);
    }

    @Test
    @DisplayName("one form and one button, with the features folded into the review")
    void featuresAndReviewSubmitTogether() throws IOException {
        // Two forms meant two buttons, and pressing the first one reopened the project — so the
        // second was refused with "still running" and a review typed at the same sitting went
        // nowhere. Reported exactly that way.
        state.setPlan(planProposing("Garage tuning"));
        state.getPlan().setNotVerified(new ArrayList<>(
                List.of("the draw branch has never been rendered")));

        String html = report(new Review());

        assertEquals(1, html.split("<form", -1).length - 1, "there must be exactly one form");
        assertEquals(1, html.split("type=\"submit\"", -1).length - 1,
                "two buttons is how a submission gets half-made");
        assertTrue(html.contains("data-kind=\"feature\""), "features are not in the form: " + html);
        assertTrue(html.contains("data-kind=\"review\""), html);

        // Folded away by default: the clips are up the page where they can be watched, and rows
        // for features settled three sessions ago sit between a reader and what they came to do.
        assertTrue(html.contains("<details class=\"features-fold\""), html);

        // The clips themselves are still above the paperwork.
        assertTrue(html.indexOf("What it does") < html.indexOf("Your review"));
    }

    @Test
    @DisplayName("the report's own script parses")
    void decisionScriptParses() throws Exception {
        // The dashboard's script has been parsed by a test since the day a stray newline in a
        // string literal blanked the whole page. This one never was, and it is the only script on
        // the page a person is asked to act on: a syntax error here leaves the report looking
        // perfectly normal and every control on it dead.
        state.setPlan(planWith("the draw branch has never been rendered"));
        String html = report(new Review());

        Matcher matcher = Pattern
                .compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
        StringBuilder all = new StringBuilder();
        int found = 0;
        while (matcher.find()) {
            all.append(matcher.group(1)).append('\n');
            found++;
        }
        assertTrue(found > 0, "the decision form carries no script:\n" + html);

        Path script = root.resolve("decide.mjs");
        Files.writeString(script, all.toString(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(nodeAvailable(),
                "node is not on PATH, so the script cannot be parsed here");

        Process check = new ProcessBuilder("node", "--check", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(check.waitFor(30, TimeUnit.SECONDS),
                "node --check did not finish");
        Assertions.assertEquals(0, check.exitValue(),
                "the decision script does not parse:\n" + output);
    }

    private boolean nodeAvailable() {
        try {
            Process probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
            return probe.waitFor(20, TimeUnit.SECONDS)
                    && probe.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private Plan planWith(String notVerified) {
        Plan plan = new Plan();
        plan.setStatus("complete");
        plan.setSummary("built it");
        plan.setNotVerified(new ArrayList<>(List.of(notVerified)));
        return plan;
    }

    @Test
    @DisplayName("the page says which feature claims were checked, and which could not be found")
    void featureClaimsAreShown() throws IOException {
        Review review = new Review();
        review.setFeatureChecks(new ArrayList<>(List.of(
                new FeatureCheck("Garage tuning", FeatureCheck.HOLDS, "Garage.java"),
                new FeatureCheck("Pink slip wager", FeatureCheck.UNPROVEN, "Race.java only logs a TODO"),
                FeatureCheck.unchecked("Leaderboard", "CODEX was unavailable: RATE_LIMITED"))));

        String html = report(review);

        assertTrue(html.contains("Pink slip wager"), html);
        assertTrue(html.contains("could not be found"));
        assertTrue(html.contains("Race.java only logs a TODO"));
        assertTrue(html.contains("found in the code"), "a claim that held up is worth saying too");
        assertTrue(html.contains("not checked"),
                "a claim nobody looked at must not read the same as one that held");
    }

    // ---------------------------------------------------------------- polish

    private void picture(String name, Instant taken) throws IOException {
        Path file = paths.repo().resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.setLastModifiedTime(file, FileTime.from(taken));
    }

    private void designChosen() throws IOException {
        Files.createDirectories(paths.designDir().resolve("CODEX"));
        Files.writeString(paths.designDir().resolve("CODEX/option-1.html"), "<html><body>neon</body></html>");
        new PolishRecord(new DesignOption("CODEX-1", "CODEX", "Neon <arcade>", "glowing outlines on black",
                "design/CODEX/option-1.html"), Instant.parse("2026-10-02T15:10:00Z")).save(paths.polishFile());
    }

    @Test
    @DisplayName("a project nobody has polished has no polish section")
    void noPolishNoSection() throws IOException {
        assertFalse(report(null).contains("id=\"polish\""));
    }

    @Test
    @DisplayName("the before and after pictures are shown side by side, each a link to itself")
    void beforeAndAfterAreShown() throws IOException {
        picture("artifacts/polish-before.png", Instant.parse("2026-10-02T16:00:00Z"));
        picture("artifacts/polish-after.png", Instant.parse("2026-10-02T16:25:00Z"));

        String html = report(null);

        assertTrue(html.contains("<h2 id=\"polish\">Polish</h2>"), html);
        assertTrue(html.contains("<img src=\"repo/artifacts/polish-before.png\" alt=\"Before the polish\">"), html);
        assertTrue(html.contains("<img src=\"repo/artifacts/polish-after.png\" alt=\"After the polish\">"), html);
        assertTrue(html.indexOf("polish-before.png") < html.indexOf("polish-after.png"), "before, then after");
        assertTrue(html.contains("Nothing checks them"), "two pictures must not be left standing as proof");
        assertFalse(html.contains("is older than"), "taken in order, so nothing to warn about");
    }

    @Test
    @DisplayName("the design that was chosen is shown beside them, framed with scripts off")
    void theChosenDesignIsShown() throws IOException {
        designChosen();
        picture("artifacts/polish-before.png", Instant.parse("2026-10-02T16:00:00Z"));

        String html = report(null);

        assertTrue(html.contains("Asked to look like <strong>Neon &lt;arcade&gt;</strong>, drawn by CODEX"), html);
        assertTrue(html.contains("glowing outlines on black"));
        assertTrue(html.contains("<iframe sandbox=\"\""), "a page a model wrote is shown with scripts off");
        assertTrue(html.contains("src=\"design/CODEX/option-1.html\""), html);
        assertTrue(html.contains("No picture has been taken since"),
                "a polish that has not finished says so instead of showing half a comparison");
    }

    @Test
    @DisplayName("the chosen design is shown from the project's own copy, which a new request for designs cannot remove")
    void theChosenDesignOutlivesTheDesignFolder() throws IOException {
        designChosen();
        Files.createDirectories(paths.repo().resolve("artifacts"));
        Files.writeString(paths.repo().resolve("artifacts/polish-design.html"), "<html><body>neon</body></html>");
        // Asking for designs again empties the folder the original came from.
        Files.delete(paths.designDir().resolve("CODEX/option-1.html"));

        String html = report(null);

        assertTrue(html.contains("src=\"repo/artifacts/polish-design.html\""), html);
        assertTrue(html.contains("Asked to look like <strong>Neon &lt;arcade&gt;</strong>"));
    }

    @Test
    @DisplayName("an after picture older than the before one is called out as left over from an earlier polish")
    void aStaleAfterPictureIsCalledOut() throws IOException {
        picture("artifacts/polish-before.png", Instant.parse("2026-10-02T16:00:00Z"));
        picture("artifacts/polish-after.png", Instant.parse("2026-09-20T09:00:00Z"));

        assertTrue(report(null).contains("is older than the “before” one"));
    }

    @Test
    @DisplayName("a review from before the per-feature pass existed renders exactly as it did")
    void reviewsWithoutChecksAreUnchanged() throws IOException {
        assertFalse(report(new Review()).contains("Feature claims"));
    }
}
