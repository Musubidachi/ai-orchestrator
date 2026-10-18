package dev.fmangoni.forge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.design.DesignBoard;
import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.MissingClip;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.jobs.polish.Polish;
import dev.fmangoni.forge.jobs.polish.PolishView;
import dev.fmangoni.forge.jobs.stop.Quiesce;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.DashboardSettings;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Comment;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.status.JobStatus;
import dev.fmangoni.forge.status.model.StatusOutward;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The dashboard's control surface, which decides what is allowed to run and when.
 *
 * <p>This class was written because {@code JobRunner} had no test at all: {@code UiServerTest}
 * passed {@code null} for the runner and the quota ledger, so every job endpoint returned an empty
 * default and the whole file went untouched. That is the part of Forge that spends money — it
 * decides whether a call reaches a vendor — and it was the least examined.
 *
 * <p>Nothing here starts an agent. Every assertion is about a decision made <em>before</em> work is
 * submitted, or about a state transition written to disk, both of which are reachable through the
 * public methods the dashboard itself calls.
 */
class JobRunnerTest {

    @TempDir
    Path root;

    private ForgeHome home;
    private QuotaLedger quota;
    private JobRunner jobs;

    /** Never invoked: every test here stops before an agent would be asked to do anything. */
    private record UnusedAgent(AgentKind kind) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            throw new AssertionError("no test in this class should reach a vendor");
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.createDirectories(home.runs());
        Files.writeString(home.houseRulesFile(), "# House rules\n");

        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, new UnusedAgent(AgentKind.CLAUDE));
        map.put(AgentKind.CODEX, new UnusedAgent(AgentKind.CODEX));

        quota = new QuotaLedger(root.resolve("quota.json"));
        jobs = new JobRunner(home, new Agents(map), 4, quota);
    }

    // ---------------------------------------------------------------- fixtures

    private void spec(String id, String title, int priority, String assign, String parent) {
        String front = """
                ---
                id: "%s"
                title: %s
                priority: %d
                assign: %s
                %sacceptance:
                  - echo ok
                ---

                ## Goal
                %s.
                """.formatted(id, title, priority, assign,
                parent == null ? "" : "parent: \"" + parent + "\"\n", title);
        try {
            Files.writeString(home.wishlist().resolve(id + "-" + title.toLowerCase().replace(' ', '-') + ".md"),
                    front, StandardCharsets.UTF_8);
            alreadyPlanned(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A plan already on disk, so these tests count the turns they were written about.
     *
     * <p>The pipeline buys a plan from the top of the ladder before the first build turn, and a run
     * started here would spend its first agent call on that. What this file is about is job control
     * — what starts, what stops, what a submission sets off — so the planning turn is taken as
     * already done. {@code PlanningTest} is where the phase itself is exercised.
     */
    private void alreadyPlanned(String id) throws IOException {
        RunPaths paths = RunPaths.under(home.runs(), id);
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), "## Approach\nBuild it.\n", StandardCharsets.UTF_8);
    }

    /** Puts a run on disk in a given phase, as if the pipeline had left it there. */
    private void runIn(String id, Phase phase, AgentKind assigned) {
        RunPaths paths = RunPaths.under(home.runs(), id);
        RunState state = new RunState();
        state.setId(id);
        state.setPhase(phase);
        state.setAssignedTo(assigned);
        RunStore.save(paths.stateFile(), state);
    }

    private RunState stateOf(String id) {
        return RunStore.load(RunPaths.under(home.runs(), id).stateFile()).orElseThrow();
    }

    private JobStatus statusOf(String id) {
        return jobs.statuses().stream()
                .filter(s -> s.identity().getId().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("no status row for " + id));
    }

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("every spec gets a row, whether or not it has ever run")
    void listsAllSpecs() {
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "codex", null);

        assertEquals(List.of("001", "002"), jobs.statuses().stream().map(s -> s.identity().getId()).toList());
    }

    @Test
    @DisplayName("an improvement is listed directly under the project it improves")
    void improvementsFollowTheirParent() {
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "claude", null);
        spec("001.1", "Alpha faster", 3, "claude", "001");

        // Priority alone would put the improvement last; it belongs with its parent instead.
        assertEquals(List.of("001", "001.1", "002"),
                jobs.statuses().stream().map(s -> s.identity().getId()).toList());
    }

    @Test
    @DisplayName("an improvement to an improvement gets a row, so a running grandchild is visible")
    void improvementsToImprovementsAreListed() {
        spec("001", "Alpha", 1, "claude", null);
        spec("001.1", "Alpha faster", 2, "claude", "001");
        spec("001.2", "Alpha faster still", 3, "claude", "001.1");

        // 001.2 used to fall through every group the board built: not a project, not an improvement
        // to a project, and not an orphan either, because 001.1 was right there in the wishlist.
        // The queue ranks improvements flat and ran it regardless, so the dashboard sat showing
        // nothing running while an agent worked, and nothing explaining what the next one awaited.
        assertEquals(List.of("001", "001.1", "001.2"),
                jobs.statuses().stream().map(s -> s.identity().getId()).toList());
    }

    // ---------------------------------------------------------------- approval

    private Decision ok(String id, String text) {
        return Decision.of(id, text, "ok", "");
    }

    private Decision noted(String id, String text, String comment) {
        return Decision.of(id, text, "ok", comment);
    }

    private Decision sendBack(String id, String text, String comment) {
        return Decision.of(id, text, "changes", comment);
    }

    @Test
    @DisplayName("nothing can be accepted until another model has read it")
    void approvalNeedsACrossReview() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        assertEquals("run the cross-review before accepting — nothing but the author has read this",
                jobs.decide("001", List.of(ok("unproven-0", "the draw branch is untested"))));
        assertEquals(Phase.DONE, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("a note survives a submission the cross-review refuses")
    void notesSurviveARefusal() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        // The reported failure: the reviewing vendor is routinely out of quota exactly when the
        // builder finishes, and everything typed in that window was discarded on submit. Whether
        // Forge may act on a review and whether it is worth keeping are different questions.
        String message = jobs.decide("001",
                List.of(noted("unproven-0", "the draw branch is untested", "worth a real test")));

        assertTrue(message.startsWith("1 note saved — "), message);
        assertEquals(List.of("worth a real test"), commentsOn("001", "unproven-0"));
    }

    @Test
    @DisplayName("a note on an item left standing is kept, not discarded with the verdict")
    void notesOnStandingItemsAreKept() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        assertEquals("accepted", jobs.decide("001",
                List.of(noted("unproven-0", "the draw branch is untested", "fine, but note it"))));

        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());
        assertEquals(List.of("fine, but note it"), commentsOn("001", "unproven-0"));
    }

    @Test
    @DisplayName("a note left beside a request reaches the next session's brief")
    void standingNotesReachTheBrief() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");

        jobs.decide("001", List.of(
                sendBack("unproven-0", "the draw branch is untested", "render it and screenshot it"),
                noted("unproven-1", "keyboard controls are untested", "fine, but say so in README")));

        String brief = stateOf("001").getUserFeedback();
        assertTrue(brief.contains("render it and screenshot it"), brief);
        assertTrue(brief.contains("fine, but say so in README"),
                "a remark on an item left standing never reached the model:\n" + brief);
        assertTrue(brief.contains("content with these"),
                "the two kinds of remark must stay distinguishable:\n" + brief);
    }

    @Test
    @DisplayName("notes accumulate across sittings rather than overwriting")
    void notesAccumulateAcrossSittings() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", Review.unavailable("reviewer unavailable: RATE_LIMITED", null));

        jobs.decide("001", List.of(noted("unproven-0", "the draw branch", "first thought")));
        jobs.decide("001", List.of(noted("unproven-0", "the draw branch", "second thought")));

        assertEquals(List.of("first thought", "second thought"), commentsOn("001", "unproven-0"));
    }

    /** A review with one blocking finding, as the reviewer wrote it. */
    private static Review blockingReview() {
        Finding finding = new Finding();
        finding.setSeverity("blocking");
        finding.setFile("src/Docx.java");
        finding.setSummary("text around tables comes out of order");
        finding.setDetail("paragraphs() walks body children but skips table cells, then appends them");
        Review review = new Review();
        review.setFindings(new ArrayList<>(List.of(finding)));
        return review;
    }

    private static Decision onTheFinding(String verdict, String note) {
        Finding finding = blockingReview().blocking().getFirst();
        return Decision.of(ReviewItem.idOf(finding), ReviewItem.textOf(finding), verdict, note);
    }

    private List<Sitting> historyOf(String id) {
        return ReviewHistory.load(RunPaths.under(home.runs(), id).reviewHistoryFile());
    }

    @Test
    @DisplayName("a submitted review goes into the history, with what came of it")
    void submissionsAreKeptAsHistory() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        jobs.decide("001", List.of(noted("unproven-0", "the draw branch is untested", "fine by me")));

        List<Sitting> history = historyOf("001");
        assertEquals(1, history.size());
        assertEquals("accepted", history.getFirst().getOutcome());
        Decision ruled = history.getFirst().getRulings().getFirst();
        assertEquals("ok", ruled.getVerdict());
        assertEquals("fine by me", ruled.getComment(), "the note as typed in that sitting");
        assertNotNull(history.getFirst().getAt());
    }

    @Test
    @DisplayName("an item left with nothing ticked decides nothing, and is not a submitted review")
    void anUnruledItemDecidesNothing() {
        // The form used to pre-tick "Fine as it is", so leaving a blocking defect alone accepted it.
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", blockingReview());

        String message = jobs.decide("001", List.of(onTheFinding("undecided", "looking into it")));

        assertTrue(message.contains("1 item still needs a ruling"), message);
        assertEquals(Phase.DONE, stateOf("001").getPhase(), "nothing was accepted");
        assertTrue(historyOf("001").isEmpty(), "an incomplete sitting is not a submitted review");
        assertEquals(List.of("looking into it"),
                commentsOn("001", ReviewItem.idOf(blockingReview().blocking().getFirst())),
                "what was typed is still kept");
    }

    @Test
    @DisplayName("a project held on findings its agent could not fix can be ruled on and accepted")
    void aHeldProjectCanBeRuledOn() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.REVIEW_REPAIR, AgentKind.CLAUDE, "abc123");
        RunState held = stateOf("001");
        held.setNeedsPerson("still not fixed after 2 attempts: text around tables comes out of order");
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), held);
        reviewed("001", blockingReview());

        assertEquals("accepted", jobs.decide("001", List.of(onTheFinding("ok", "tables are rare here"))));

        RunState after = stateOf("001");
        assertEquals(Phase.ACCEPTED, after.getPhase());
        assertNull(after.getNeedsPerson(), "accepting answers the hold");
    }

    @Test
    @DisplayName("a finding sent back reaches the agent with the reviewer's explanation, not one line")
    void sentBackFindingsCarryTheReviewersDetail() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", blockingReview());
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");

        jobs.decide("001", List.of(onTheFinding("changes", "")));

        String brief = stateOf("001").getUserFeedback();
        assertTrue(brief.contains("text around tables comes out of order"), brief);
        assertTrue(brief.contains("What the reviewer found: paragraphs() walks body children"), brief);
        assertFalse(brief.contains("what you reported"), "the agent did not report a reviewer's finding");
    }

    // ------------------------------------------------------- the Fix Blocking Findings button

    /** A finished project with its reviewer's blocking findings recorded on it, as the loop leaves them. */
    private void finishedWithBlockers(String id, Phase phase) {
        spec(id, "Alpha", 1, "claude", null);
        runIn(id, phase, AgentKind.CLAUDE, "abc123");
        RunState state = stateOf(id);
        state.setBlockingFindings(new ArrayList<>(blockingReview().blocking()));
        RunStore.save(RunPaths.under(home.runs(), id).stateFile(), state);
        reviewed(id, blockingReview());
        // The vendor is closed, so the button's own work can be read off the state without a turn
        // being run: it is saved before the start is attempted.
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");
    }

    @Test
    @DisplayName("Fix Blocking Findings sends a finished project's findings straight back to its agent")
    void fixBlockingSendsThemBack() {
        finishedWithBlockers("001", Phase.DONE);

        jobs.fixBlocking("001");

        RunState state = stateOf("001");
        assertEquals(Phase.REVIEW_REPAIR, state.getPhase(), "the next turn is the repair, not a review");
        Finding finding = state.getBlockingFindings().getFirst();
        assertEquals(ReviewItem.idOf(finding), finding.getId(), "named, so the next review can rule on it");
        assertEquals(1, finding.getAttempts(), "this is its first attempt");
        assertEquals(1, state.getReviewRepairs());
        assertNull(state.getNeedsPerson());
    }

    @Test
    @DisplayName("a project held on findings its agent could not fix gets a fresh budget from the button")
    void fixBlockingStartsAgainFromAHold() {
        finishedWithBlockers("001", Phase.REVIEW_REPAIR);
        RunState held = stateOf("001");
        held.getBlockingFindings().getFirst().setAttempts(2);
        held.setReviewRepairs(4);
        held.setNeedsPerson("still not fixed after 2 attempts: text around tables comes out of order");
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), held);

        jobs.fixBlocking("001");

        RunState state = stateOf("001");
        assertNull(state.getNeedsPerson(), "pressing it is the person answering the hold");
        assertEquals(1, state.getBlockingFindings().getFirst().getAttempts());
        assertEquals(1, state.getReviewRepairs());
    }

    @Test
    @DisplayName("findings recorded only in the review, from before the state kept them, are still found")
    void fixBlockingReadsTheReviewWhenTheStateHasNone() {
        finishedWithBlockers("001", Phase.DONE);
        RunState state = stateOf("001");
        state.setBlockingFindings(new ArrayList<>());
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), state);

        jobs.fixBlocking("001");

        assertEquals(Phase.REVIEW_REPAIR, stateOf("001").getPhase());
        assertEquals(1, stateOf("001").getBlockingFindings().size());
    }

    @Test
    @DisplayName("with nothing blocking, the button changes nothing and says so")
    void fixBlockingNeedsSomethingToFix() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        assertEquals("there are no blocking findings to fix", jobs.fixBlocking("001"));
        assertEquals(Phase.DONE, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("a parked or accepted project is not sent for a review repair")
    void fixBlockingRefusesWhereTheGateHasNotPassedOrADecisionWasMade() {
        finishedWithBlockers("001", Phase.PARKED);
        RunState parked = stateOf("001");
        parked.setLastError("acceptance still failing after 4 repair rounds");
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), parked);

        // A review repair tells the agent its acceptance commands pass. Here they do not.
        String message = jobs.fixBlocking("001");
        assertTrue(message.contains("parked on something else")
                && message.contains("acceptance still failing"), message);
        assertEquals(Phase.PARKED, stateOf("001").getPhase());

        RunState accepted = stateOf("001");
        accepted.setPhase(Phase.ACCEPTED);
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), accepted);
        assertTrue(jobs.fixBlocking("001").startsWith("already accepted"));
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase(), "an acceptance is never undone by a button");
    }

    // ------------------------------------------------------- Polish: choosing a design, and building it

    /** A finished project whose vendor is closed, so the button's work can be read off the state. */
    private void finished(String id, Phase phase) {
        spec(id, "Alpha", 1, "claude", null);
        runIn(id, phase, AgentKind.CLAUDE, "abc123");
        reviewed(id, new Review());
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");
    }

    /** Two designs on the board, as the vendors would have left them. */
    private void designsOffered(String id) throws IOException {
        RunPaths paths = RunPaths.under(home.runs(), id);
        Files.createDirectories(paths.designDir().resolve("CODEX"));
        Files.writeString(paths.designDir().resolve("CODEX/option-1.html"), "<html><body class=\"neon\">neon</body></html>");
        Files.writeString(paths.designDir().resolve("CODEX/option-2.html"), "<html><body class=\"paper\">paper</body></html>");
        new DesignBoard(Instant.now(), "", DesignBoard.READY, List.of(
                new DesignOption("CODEX-1", "CODEX", "Neon arcade", "glowing outlines on black", "design/CODEX/option-1.html"),
                new DesignOption("CODEX-2", "CODEX", "Paper cut", "flat shapes with soft shadows", "design/CODEX/option-2.html")),
                List.of(), "", null, "", "").save(paths.designBoardFile());
    }

    @Test
    @DisplayName("building a chosen design sends the project back to match that picture, which is put in its repository")
    void buildingAChosenDesign() throws IOException {
        finished("001", Phase.DONE);
        designsOffered("001");
        RunState spent = stateOf("001");
        spent.setReviewRepairs(4);
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), spent);

        jobs.buildDesign("001", "CODEX-2", "  keep the score readable ", "");

        RunState state = stateOf("001");
        assertEquals(0, state.getReviewRepairs(), "a new request's review loop is its own (003's theme was held on its first finding)");
        assertEquals(Phase.CONTINUING, state.getPhase(), "delivered as a request, the way any feedback is");
        String request = state.getUserFeedback();
        assertTrue(request.startsWith("Polish how this looks."), request);
        assertTrue(request.contains("chose one: \"Paper cut\" — flat shapes with soft shadows"), request);
        assertTrue(request.contains("`artifacts/polish-design.html`"), request);
        assertTrue(request.contains("a picture of where to get to, not code to paste in"), request);
        assertTrue(request.contains("What I particularly want: keep the score readable"), request);
        assertTrue(request.contains("Do not change what it does"), "what must not move is most of the request");
        assertTrue(request.contains("Do not weaken or delete any test"), request);
        assertTrue(request.contains("artifacts/polish-before.png") && request.contains("artifacts/polish-after.png"),
                request);

        RunPaths paths = RunPaths.under(home.runs(), "001");
        assertTrue(Files.readString(paths.repo().resolve("artifacts/polish-design.html")).contains("paper"),
                "the page that was picked is the one the builder is given");
        assertEquals("CODEX-2", jobs.polishView("001").orElseThrow().board().chosen());
    }

    @Test
    @DisplayName("with no design chosen the look is left to the agent, and nothing is put in the repository")
    void polishWithoutChoosing() {
        finished("001", Phase.DONE);

        jobs.buildDesign("001", "", "", "");

        String request = stateOf("001").getUserFeedback();
        assertTrue(request.contains("Improve the graphics and the visual presentation"), request);
        assertFalse(request.contains("chose one"), request);
        assertFalse(request.contains("What I particularly want"), request);
        assertFalse(Files.exists(RunPaths.under(home.runs(), "001").repo().resolve("artifacts/polish-design.html")));
    }

    @Test
    @DisplayName("a design that is not on the board starts nothing")
    void buildingADesignThatIsNotThere() throws IOException {
        finished("001", Phase.DONE);
        designsOffered("001");

        String message = jobs.buildDesign("001", "CLAUDE-7", "", "");

        assertTrue(message.startsWith("there is no design CLAUDE-7 to build"), message);
        assertEquals(Phase.DONE, stateOf("001").getPhase());
        assertNull(stateOf("001").getUserFeedback());
    }

    @Test
    @DisplayName("feedback already waiting to be delivered goes with the polish, not under it")
    void polishKeepsWhatWasAlreadyWaiting() {
        finished("001", Phase.DONE);
        RunState waiting = stateOf("001");
        waiting.setUserFeedback("The restart button is too small.");
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), waiting);

        jobs.buildDesign("001", "", "", "");

        String request = stateOf("001").getUserFeedback();
        assertTrue(request.startsWith("The restart button is too small."), request);
        assertTrue(request.contains("Polish how this looks."), request);
    }

    @Test
    @DisplayName("a request for designs that was cut off is shown as interrupted, with what arrived, and not as still asking")
    void aStaleRequestIsInterrupted() throws IOException {
        // Left saying "asking" by an editor that stopped mid-request, the page waited for ever and
        // would not let another request be made.
        finished("001", Phase.DONE);
        designsOffered("001");
        RunPaths paths = RunPaths.under(home.runs(), "001");
        DesignBoard ready = DesignBoard.load(paths.designBoardFile());
        new DesignBoard(ready.askedAt(), "", DesignBoard.ASKING, ready.options(), List.of(), "", null, "", "")
                .save(paths.designBoardFile());

        PolishView view = jobs.polishView("001").orElseThrow();

        assertFalse(view.running());
        assertEquals(DesignBoard.INTERRUPTED, view.board().state());
        assertEquals(2, view.board().options().size(), "what arrived is still there to choose from");
        assertEquals("Alpha", view.title());
        assertEquals("", view.blocked());
    }

    @Test
    @DisplayName("designs can be suggested for any project that has run, and the page is told what Build will do")
    void designsAreSuggestedWhereverItHasRun() {
        spec("002", "Beta", 2, "claude", null);
        assertEquals("it has not run yet", jobs.polishView("002").orElseThrow().blocked());

        finished("001", Phase.DONE);
        assertEquals(Polish.NOW, jobs.polishView("001").orElseThrow().buildAs());
        for (Phase phase : List.of(Phase.PARKED, Phase.REPAIRING, Phase.SUSPENDED)) {
            RunState state = stateOf("001");
            state.setPhase(phase);
            RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), state);
            PolishView view = jobs.polishView("001").orElseThrow();
            assertEquals("", view.blocked(), "asking changes nothing in the project, so " + phase + " may ask");
            assertEquals(Polish.LATER, view.buildAs(), phase.name());
        }
        RunState accepted = stateOf("001");
        accepted.setPhase(Phase.ACCEPTED);
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), accepted);
        assertEquals(Polish.ENHANCEMENT, jobs.polishView("001").orElseThrow().buildAs());
        assertTrue(jobs.polishView("999").isEmpty(), "an unknown id is answered, not thrown");
    }

    @Test
    @DisplayName("a design picked for an unfinished project is kept, and nothing about the project changes")
    void anUnfinishedProjectKeepsThePick() throws IOException {
        finished("001", Phase.PARKED);
        designsOffered("001");

        String message = jobs.buildDesign("001", "CODEX-2", "", "");

        assertTrue(message.startsWith("kept \"Paper cut\""), message);
        RunState state = stateOf("001");
        assertEquals(Phase.PARKED, state.getPhase());
        assertNull(state.getUserFeedback(), "no session is sent anything");
        assertFalse(Files.exists(RunPaths.under(home.runs(), "001").repo().resolve("artifacts/polish-design.html")),
                "nothing goes into a repository the next session would commit as its own work");
        PolishView view = jobs.polishView("001").orElseThrow();
        assertEquals("CODEX-2", view.board().chosen());
        assertTrue(view.kept());
        assertEquals("Paper cut", statusOf("001").outward().getDesignKept(), "the dashboard remembers it");

        assertTrue(jobs.buildDesign("001", "", "", "").contains("pick a design to keep"),
                "leaving the look to the agent can wait until it is finished");
    }

    @Test
    @DisplayName("an extra is added beside the look: the first one makes the pack slot, and the default look is not let go")
    void anExtraIsAddedBesideTheLook() throws IOException {
        finished("001", Phase.DONE);
        designsOffered("001");
        RunPaths paths = RunPaths.under(home.runs(), "001");
        Files.writeString(paths.lookReference(), "a reference picture");

        assertTrue(jobs.buildDesign("001", "", "", DesignBoard.THEME).startsWith("pick a design for the theme"),
                "the pack is named after the design");
        jobs.buildDesign("001", "CODEX-2", "for Christmas", DesignBoard.THEME);

        String request = stateOf("001").getUserFeedback();
        assertTrue(request.startsWith("Add \"Paper cut\" to how this program looks: a theme"), request);
        assertTrue(request.contains("The program already has a place for packs"),
                "a reference is kept, so it has one: " + request);
        assertTrue(request.contains("What I particularly want: for Christmas"), request);
        assertTrue(request.contains("`artifacts/polish-design.html`"), request);
        assertEquals("Paper cut", stateOf("001").getPolishDesign(), "reviewed against the picture");
        assertTrue(Files.exists(paths.lookReference()), "an extra never lets the default look go");
    }

    @Test
    @DisplayName("the first extra makes the place packs go; replacing the look lets the old default go")
    void theFirstExtraMakesTheSlot() throws IOException {
        finished("001", Phase.DONE);
        designsOffered("001");
        RunPaths paths = RunPaths.under(home.runs(), "001");
        DesignBoard drawn = DesignBoard.load(paths.designBoardFile());
        new DesignBoard(drawn.askedAt(), "", DesignBoard.READY, drawn.options(), List.of(), "", null, "",
                DesignBoard.VARIANTS).save(paths.designBoardFile());

        // Drawn as variants, so built as variants even from a page that did not say.
        jobs.buildDesign("001", "CODEX-1", "", "");

        String request = stateOf("001").getUserFeedback();
        assertTrue(request.startsWith("Add \"Neon arcade\" to how this program looks: a set of variants"), request);
        assertTrue(request.contains("The program has no place for packs yet, so make one first"), request);
        assertTrue(request.contains("as `artifacts/look-default.png` on every run"), request);

        finished("002", Phase.DONE);
        designsOffered("002");
        RunPaths other = RunPaths.under(home.runs(), "002");
        Files.writeString(other.lookReference(), "the old default");
        jobs.buildDesign("002", "CODEX-1", "", "");
        assertFalse(Files.exists(other.lookReference()), "a new look on purpose: the next gate adopts it");
    }

    @Test
    @DisplayName("an extra for an accepted project is an enhancement named after the pack")
    void anExtraForAnAcceptedProject() throws IOException {
        finished("001", Phase.ACCEPTED);
        designsOffered("001");

        String message = jobs.buildDesign("001", "CODEX-1", "", DesignBoard.ANIMATION);

        assertTrue(message.contains(": added 001.1 \"Alpha — Neon arcade animation\""), message);
        Spec enhancement = Wishlist.load(home.wishlist()).byId("001.1").orElseThrow();
        assertTrue(enhancement.body().contains("Add \"Neon arcade animation\" to Alpha, beside the look it has now."),
                enhancement.body());
        assertTrue(enhancement.body().contains("an animation, new motion"), enhancement.body());
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("more like a design needs that design on the board, and a note saying what to change in it")
    void moreLikeNeedsADesignAndANote() throws IOException {
        finished("001", Phase.DONE);
        designsOffered("001");

        assertTrue(jobs.askForDesigns("001", "vary the artwork", "CODEX-9", "").startsWith("there is no design CODEX-9 to start from"));
        assertTrue(jobs.askForDesigns("001", "  ", "CODEX-2", "").startsWith("say what to change in \"Paper cut\" first"),
                "what to change is the whole of such a request");
        assertEquals(2, jobs.polishView("001").orElseThrow().board().options().size(), "neither touched the board");
    }

    @Test
    @DisplayName("what was typed beside a design kept for later is kept with it, and a new pick replaces it")
    void aKeptDesignKeepsItsNote() throws IOException {
        // 004: a note typed beside a design kept for later was dropped without a word, and only the
        // picture would have reached the session that built it.
        finished("001", Phase.PARKED);
        designsOffered("001");

        String message = jobs.buildDesign("001", "CODEX-2", "  clowns seen from above instead of the x ", "");

        assertTrue(message.startsWith("kept \"Paper cut\" and your note"), message);
        assertEquals("clowns seen from above instead of the x",
                jobs.polishView("001").orElseThrow().board().chosenNote());
        assertNull(stateOf("001").getUserFeedback(), "still nothing is sent to a session");

        jobs.buildDesign("001", "CODEX-1", "", "");
        assertEquals("", jobs.polishView("001").orElseThrow().board().chosenNote(),
                "the note belongs to the pick it was typed beside");
    }

    @Test
    @DisplayName("a design built for an accepted project becomes an enhancement, and the acceptance stands")
    void anAcceptedProjectGetsAnEnhancement() throws IOException {
        finished("001", Phase.ACCEPTED);
        designsOffered("001");

        String message = jobs.buildDesign("001", "CODEX-1", "brighter", "");

        assertTrue(message.contains(": added 001.1 \"Alpha visual update\""), message);
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase(), "an acceptance is never undone by a button");
        Spec enhancement = Wishlist.load(home.wishlist()).byId("001.1").orElseThrow();
        assertEquals("001", enhancement.parentId().orElseThrow());
        assertEquals(List.of("echo ok"), enhancement.acceptance(),
                "the parent's own commands, so a restyle that breaks what was accepted is not accepted");
        assertTrue(enhancement.body().contains("\"Neon arcade\""), enhancement.body());
        assertTrue(enhancement.body().contains("Do not change what it does"), enhancement.body());
        assertTrue(Files.readString(RunPaths.under(home.runs(), "001").repo()
                .resolve("artifacts/polish-design.html")).contains("neon"),
                "the enhancement runs in this repository, and finds the design there");
    }

    // ------------------------------------------------------- the quick review's cards

    @Test
    @DisplayName("the quick review is dealt from the same project a submission would be filed against")
    void reviewDeckFindsTheProject() {
        finishedWithBlockers("001", Phase.DONE);

        var deck = jobs.reviewDeck("001").orElseThrow();

        assertEquals("001", deck.getId());
        assertEquals(1, deck.getMustDecide().size(), "the blocking finding has to be answered on its own");
        assertEquals(ReviewItem.idOf(blockingReview().blocking().getFirst()), deck.getMustDecide().getFirst().getId(),
                "the same id the form rules on, or a ruling made here would be a different ruling");
        assertTrue(jobs.reviewDeck("999").isEmpty());
    }

    private List<String> commentsOn(String id, String itemId) {
        return DecisionStore
                .load(RunPaths.under(home.runs(), id).decisionsFile()).stream()
                .filter(d -> d.getItemId().equals(itemId))
                .flatMap(d -> d.getComments().stream())
                .map(Comment::text)
                .toList();
    }

    @Test
    @DisplayName("letting every item stand accepts the project")
    void everythingStandingAccepts() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        assertEquals("accepted", jobs.decide("001",
                List.of(ok("unproven-0", "the draw branch is untested"),
                        ok("unproven-1", "keyboard controls are untested"))));
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("a request left on a claim the agent has dropped no longer blocks accepting")
    void staleRequestsDoNotBlockAcceptance() {
        // Found on a real project here: four "needs work" rulings on claims the agent had since
        // stopped making. The report shows those read-only — there is no radio button on them —
        // so nothing on the page could ever withdraw them, and every future submission would have
        // refused to accept and reopened the project against four dead sentences instead.
        spec("001", "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState state = new RunState();
        state.setId("001");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setBaselineCommit("abc123");
        Plan plan = new Plan();
        plan.setSummary("built it");
        plan.setNotVerified(List.of("the only claim still being made"));
        state.setPlan(plan);
        RunStore.save(paths.stateFile(), state);
        reviewed("001", new Review());

        DecisionStore.save(paths.decisionsFile(), List.of(
                sendBack("unproven-4", "a claim the agent has since stopped making",
                        "this needs a real test")));

        String live = ReviewItem
                .of(plan, new Review()).get(0).id();
        assertEquals("accepted", jobs.decide("001",
                List.of(ok(live, "the only claim still being made"))));
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());

        // Kept, though. The verdict expires with the claim; what a person wrote does not.
        assertEquals(List.of("this needs a real test"), commentsOn("001", "unproven-4"));
    }

    @Test
    @DisplayName("sending anything back reopens the project with those items as the brief")
    void sendingItemsBackStartsASession() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        jobs.decide("001", List.of(
                ok("unproven-0", "keyboard controls are untested"),
                sendBack("unproven-1", "the draw branch has never been rendered",
                        "render it and take a screenshot")));

        RunState state = stateOf("001");
        assertEquals(Phase.CONTINUING, state.getPhase());
        assertTrue(state.getUserFeedback().contains("the draw branch has never been rendered"),
                "the agent must see which of its own claims came back");
        assertTrue(state.getUserFeedback().contains("render it and take a screenshot"));
        assertFalse(state.getUserFeedback().contains("keyboard controls are untested"),
                "an item that was allowed to stand is not a change request");
    }

    // ---------------------------------------------------------------- features

    /** A finished project whose handoff proposed these features. */
    private RunPaths withFeatures(String id, String... names) {
        spec(id, "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), id);
        RunState state = new RunState();
        state.setId(id);
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setBaselineCommit("abc123");
        Plan plan = new Plan();
        plan.setSummary("built it");
        for (String name : names) {
            plan.getFeatures().add(new PlanFeature(name, "click it"));
        }
        state.setPlan(plan);
        RunStore.save(paths.stateFile(), state);
        reviewed(id, new Review());
        return paths;
    }

    private Decision approve(String name) {
        Feature feature = new Feature(name, "");
        return Decision.of(feature.id(), name, Features.APPROVED, "");
    }

    private Decision decline(String name, String why) {
        Feature feature = new Feature(name, "");
        return Decision.of(feature.id(), name, Features.DECLINED, why);
    }

    @Test
    @DisplayName("approving a feature reopens the project, because it is asking for work")
    void approvingAFeatureStartsASession() {
        // The point of the whole mechanism: a recording is not a nicety that might get done, it is
        // part of the contract, and saying yes to one is commissioning it.
        withFeatures("001", "Report export");
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        String message = jobs.decide("001", List.of(), List.of(approve("Report export")));

        assertEquals(Phase.CONTINUING, stateOf("001").getPhase());
        assertTrue(message.contains("needs a recording"), message);
    }

    @Test
    @DisplayName("declining costs nothing and is remembered")
    void decliningOwesNothing() {
        RunPaths paths = withFeatures("001", "Report export");

        String message = jobs.decide("001", List.of(), List.of(decline("Report export", "it is just a form")));

        assertEquals(Phase.DONE, stateOf("001").getPhase(), "a no must not start a session");
        assertTrue(message.contains("saved"), message);
        assertEquals(List.of("it is just a form"),
                DecisionStore.load(paths.featuresFile()).stream()
                        .flatMap(d -> d.getComments().stream())
                        .map(Comment::text).toList());
    }

    @Test
    @DisplayName("approving something already filmed asks for nothing")
    void alreadyFilmedFeaturesOweNothing() throws IOException {
        RunPaths paths = withFeatures("001", "Report export");
        Clips.write(
                paths.repo().resolve(new Feature("Report export", "").recordingPath(".webm")));

        String message = jobs.decide("001", List.of(), List.of(approve("Report export")));

        assertEquals(Phase.DONE, stateOf("001").getPhase());
        assertTrue(message.contains("saved"), message);
    }

    // ------------------------------------------------- one clip owed under two names, and Check again

    private static final Feature CUSTOMIZATION = new Feature("Report customization", "");

    /** 003 as it parked: one feature approved under two names, the clip made under only one. */
    private RunPaths parkedOnAClip(String id) throws IOException {
        RunPaths paths = withFeatures(id, "Report export", "Monthly summary");
        RunState state = stateOf(id);
        state.setPhase(Phase.PARKED);
        state.setSessions(23);
        state.setLastError("still incomplete after 23 sessions");
        state.setLastFailure(new CommandResult("recordings: 1 feature(s) unfilmed", 1,
                "Report customization — no recording was produced", false, Duration.ZERO, ""));
        RunStore.save(paths.stateFile(), state);
        DecisionStore.save(paths.featuresFile(), List.of(approve("Report customization"),
                approve("Report export"), approve("Monthly summary")));
        Clips.write(paths.repo().resolve(new Feature("Report export", "").recordingPath(".webm")));
        Clips.write(paths.repo().resolve(new Feature("Monthly summary", "").recordingPath(".webm")));
        return paths;
    }

    @Test
    @DisplayName("a project parked on a clip asks about it, and what it might be the same feature as")
    void theMissingClipIsAQuestion() throws IOException {
        parkedOnAClip("001");

        List<MissingClip> asked = statusOf("001").review().getUnfilmed();

        assertEquals(1, asked.size(), asked.toString());
        assertEquals("Report customization", asked.get(0).name());
        assertFalse(asked.get(0).listed(), "the agent stopped listing it, which is the clue");
        assertEquals("Report export", asked.get(0).sameAs().get(0));
    }

    @Test
    @DisplayName("the same feature as a filmed one: declined, with why, and nothing started")
    void sameFeatureDeclinesTheOldName() throws IOException {
        RunPaths paths = parkedOnAClip("001");

        String message = jobs.ruleFeature("001", CUSTOMIZATION.id(), Features.DECLINED, "Report export");

        assertTrue(message.contains("no longer needs a clip") && message.contains("Check Again"), message);
        Decision ruled = Features.byId(DecisionStore.load(paths.featuresFile())).get(CUSTOMIZATION.id());
        assertEquals(Features.DECLINED, ruled.getVerdict());
        assertTrue(ruled.latestComment().contains("Same feature as \"Report export\""), ruled.latestComment());
        assertEquals(Features.APPROVED, Features.byId(DecisionStore.load(paths.featuresFile()))
                .get(new Feature("Report export", "").id()).getVerdict(), "the name that stays is untouched");
        assertEquals(Phase.PARKED, stateOf("001").getPhase(), "a ruling starts nothing");
        assertTrue(statusOf("001").review().getUnfilmed().isEmpty(), "nothing is owed a clip now");
    }

    @Test
    @DisplayName("a feature ruling refuses what is not a feature, a verdict that is neither, and an unknown twin")
    void featureRulingsAreChecked() throws IOException {
        RunPaths paths = parkedOnAClip("001");

        assertTrue(jobs.ruleFeature("001", "feature-hovercraft", Features.DECLINED, "")
                .startsWith("there is no feature"));
        assertTrue(jobs.ruleFeature("001", CUSTOMIZATION.id(), "maybe", "").contains("either still wanted"));
        assertTrue(jobs.ruleFeature("001", CUSTOMIZATION.id(), Features.DECLINED, "Hovercraft")
                .contains("no feature called Hovercraft"));
        assertEquals(Features.APPROVED, Features.byId(DecisionStore.load(paths.featuresFile()))
                .get(CUSTOMIZATION.id()).getVerdict(), "nothing refused was saved");
    }

    @Test
    @DisplayName("still wanted, with a note: the note goes to the next session, and the question reads as answered")
    void stillWantedWithANote() throws IOException {
        RunPaths paths = parkedOnAClip("001");

        String message = jobs.ruleFeature("001", CUSTOMIZATION.id(), Features.APPROVED, "",
                "it should use real Google ads, not a placeholder");

        assertTrue(message.contains("with your note") && message.contains("Run Another Session"), message);
        RunState state = stateOf("001");
        assertEquals(Phase.PARKED, state.getPhase(), "a ruling starts nothing");
        assertTrue(state.getUserFeedback().contains("real Google ads"), state.getUserFeedback());
        assertTrue(state.getUserFeedback().contains("artifacts/feature-report-customization.webm"),
                "it is told the clip is still owed, under the name it is owed by");
        Decision ruled = Features.byId(DecisionStore.load(paths.featuresFile())).get(CUSTOMIZATION.id());
        assertEquals(Features.APPROVED, ruled.getVerdict());
        assertFalse(ruled.hasUnheardComments(), "handed over with the brief, so not delivered a second time");
        assertTrue(statusOf("001").review().getUnfilmed().get(0).kept(), "the dashboard shows it answered");
    }

    @Test
    @DisplayName("Check again sends a parked project to its gate alone, spending no session")
    void recheckIsTheGateAlone() throws IOException {
        parkedOnAClip("001");
        // The vendor is closed, so the start is refused and what the button set is left to read.
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");

        jobs.recheck("001");

        RunState state = stateOf("001");
        assertEquals(Phase.GATING, state.getPhase());
        assertTrue(state.isGateOnly());
        assertEquals(23, state.getSessions(), "no session was spent or granted");
    }

    @Test
    @DisplayName("Check again is refused where there is no failed gate to check")
    void recheckRefusesWithoutAFailedGate() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE);
        assertTrue(jobs.recheck("001").startsWith("only a parked project"));

        RunState parked = stateOf("001");
        parked.setPhase(Phase.PARKED);
        parked.setLastError("the review found the gate had never passed");
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), parked);
        String message = jobs.recheck("001");

        assertTrue(message.contains("did not park on its gate"), message);
        assertEquals(Phase.PARKED, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("an accepted project is not silently reopened to film something")
    void acceptedProjectsAreNotReopenedBehindYou() {
        // Acceptance is the one decision a person makes about a project. Withdrawing it as a side
        // effect of ticking a checkbox elsewhere on the page is not a thing a tool should do.
        RunPaths paths = withFeatures("001", "Report export");
        RunState state = stateOf("001");
        state.setPhase(Phase.ACCEPTED);
        RunStore.save(paths.stateFile(), state);

        String message = jobs.decide("001", List.of(), List.of(approve("Report export")));

        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());
        assertTrue(message.contains("Reopen"), message);
    }

    @Test
    @DisplayName("a feature you ask for yourself is owed, though nothing proposed it")
    void featuresYouAskForAreCommissioned() {
        // Without this the list is whatever the agent happened to mention, and anything it left
        // out is something you can never ask to see — which quietly hands the agent authorship of
        // the list after all.
        withFeatures("001");
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        // No id: the page sends the words, and the name-to-filename rule stays in one place.
        Decision asked = Decision.of("", "Offline mode", Features.APPROVED,
                "pull the network and show it still saves");

        String message = jobs.decide("001", List.of(), List.of(asked));

        assertEquals(Phase.CONTINUING, stateOf("001").getPhase());
        assertTrue(message.contains("needs a recording"), message);
        assertEquals("feature-offline-mode",
                DecisionStore.load(RunPaths.under(home.runs(), "001").featuresFile())
                        .get(0).getItemId(),
                "the id must be derived from the name, or the gate waits for a file at a path "
                        + "nobody is writing to");
    }

    @Test
    @DisplayName("asking for something already filmed asks for nothing again")
    void askingForSomethingAlreadyFilmedIsIdempotent() throws IOException {
        RunPaths paths = withFeatures("001", "Report export");
        Clips.write(
                paths.repo().resolve(new Feature("Report export", "").recordingPath(".webm")));

        // Different capitalisation, same feature: the slug is what identifies it.
        String message = jobs.decide("001", List.of(), List.of(Decision.of("", "report EXPORT", Features.APPROVED, "")));

        assertEquals(Phase.DONE, stateOf("001").getPhase());
        assertTrue(message.contains("saved"), message);
        assertEquals(1, DecisionStore.load(paths.featuresFile()).size(),
                "a second row for the same feature would owe a second recording of it");
    }

    @Test
    @DisplayName("a change asked of a feature reaches the agent, even once it is filmed")
    void featureChangesReachTheBrief() throws IOException {
        // Reported: "if a batch fails, offer an ad to buy more credit" was
        // typed beside an approved feature, saved, shown — and the word "credit" never appeared in
        // any agent's log. The session was told to film the feature and nothing else.
        RunPaths paths = withFeatures("001", "Empty report reset");
        Clips.write(
                paths.repo().resolve(new Feature("Empty report reset", "").recordingPath(".webm")));
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        Decision change = Decision.of(new Feature("Empty report reset", "").id(),
                "Empty report reset", Features.CHANGES,
                "offer an ad to buy more credit");
        jobs.decide("001", List.of(), List.of(change));

        RunState state = stateOf("001");
        assertEquals(Phase.CONTINUING, state.getPhase(), "a filmed feature owes nothing, but a "
                + "change to it is still work");
        assertTrue(state.getUserFeedback().contains("credit"), "the request never reached the "
                + "brief:\n" + state.getUserFeedback());
        assertTrue(state.getUserFeedback().contains("recording shows it working the new way"),
                state.getUserFeedback());
    }

    @Test
    @DisplayName("a change, once handed to a session, does not reopen the project again")
    void featureChangesSettleOnceDelivered() throws IOException {
        // Otherwise the verdict outlives the work: the change is built, the project finishes, and
        // the very submission meant to accept it finds "needs changes" still on record.
        RunPaths paths = withFeatures("001", "Empty report reset");
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");
        jobs.decide("001", List.of(), List.of(Decision.of(
                new Feature("Empty report reset", "").id(), "Empty report reset",
                Features.CHANGES, "offer more credit")));

        Decision stored = DecisionStore.load(paths.featuresFile()).get(0);
        assertEquals(Features.APPROVED, stored.getVerdict());
        assertEquals(List.of("offer more credit"),
                stored.getComments().stream().map(Comment::text).toList(),
                "the note is kept; only the instruction has been carried out");
    }

    @Test
    @DisplayName("a remark beside a feature travels with the session it starts")
    void featureRemarksTravelWithRecordingWork() {
        // The recording-only branch set the brief to null, which is exactly how the reported note
        // was lost: the feature was unfilmed, so a session started — told nothing but "film it".
        withFeatures("001", "Empty report reset");
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        jobs.decide("001", List.of(), List.of(Decision.of(
                new Feature("Empty report reset", "").id(), "Empty report reset",
                Features.APPROVED, "slight modification: offer an ad for more credit")));

        String brief = stateOf("001").getUserFeedback();
        assertNotNull(brief, "the session was started with nothing from the person");
        assertTrue(brief.contains("credit"), brief);
    }

    @Test
    @DisplayName("a note beside a filmed feature is not accepted over, and is delivered once")
    void featureNotesAreNotAcceptedOver() throws IOException {
        // 003 as it stands: every feature filmed and approved, the review clean, and "Slight
        // modification … offer an AD to purchase more credit" under Worth seeing.
        // Submitting that accepted the project, and the request was never read by an agent.
        RunPaths paths = withFeatures("001", "Empty report reset");
        Clips.write(
                paths.repo().resolve(new Feature("Empty report reset", "").recordingPath(".webm")));
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");
        String id = new Feature("Empty report reset", "").id();

        String message = jobs.decide("001", List.of(ok("unproven-0", "the draw branch is untested")),
                List.of(Decision.of(id, "Empty report reset", Features.APPROVED,
                        "slight modification: offer an ad to buy more credit")));

        RunState state = stateOf("001");
        assertEquals(Phase.CONTINUING, state.getPhase(), message);
        assertTrue(state.getUserFeedback().contains("credit"), state.getUserFeedback());
        assertTrue(message.contains("note on a feature"), message);

        // The session runs and finishes; the same form submitted again, with nothing new typed.
        state.setPhase(Phase.DONE);
        state.setUserFeedback(null);
        RunStore.save(paths.stateFile(), state);

        assertEquals("accepted", jobs.decide("001",
                List.of(ok("unproven-0", "the draw branch is untested")),
                List.of(Decision.of(id, "Empty report reset", Features.APPROVED, ""))));
    }

    @Test
    @DisplayName("a feature note already delivered is not sent with the next session")
    void deliveredFeatureNotesAreNotRepeated() throws IOException {
        RunPaths paths = withFeatures("001", "Empty report reset", "Report export");
        Clips.write(
                paths.repo().resolve(new Feature("Empty report reset", "").recordingPath(".webm")));
        Clips.write(
                paths.repo().resolve(new Feature("Report export", "").recordingPath(".webm")));
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");
        jobs.decide("001", List.of(), List.of(Decision.of(new Feature("Empty report reset", "").id(),
                "Empty report reset", Features.APPROVED, "offer more credit")));
        RunState state = stateOf("001");
        state.setPhase(Phase.DONE);
        RunStore.save(paths.stateFile(), state);

        jobs.decide("001", List.of(), List.of(Decision.of(new Feature("Report export", "").id(),
                "Report export", Features.APPROVED, "let paint be metallic")));

        String brief = stateOf("001").getUserFeedback();
        assertTrue(brief.contains("metallic"), brief);
        assertFalse(brief.contains("credit"), "an old remark went back as though it were new:\n" + brief);
    }

    @Test
    @DisplayName("an idea taken up is asked for as something to build, not something to film")
    void approvedIdeasAreBuildRequests() {
        // An agent told to film a feature that does not exist will film whatever screen is nearest.
        withFeatures("001", "Report export");
        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState state = stateOf("001");
        state.getPlan().getIdeas().add(new PlanFeature(
                "Rival leaderboard", "a table of the bots you have beaten"));
        RunStore.save(paths.stateFile(), state);
        quota.record(AgentKind.CLAUDE, Instant.now().plus(1, ChronoUnit.HOURS), "limit");

        jobs.decide("001", List.of(), List.of(Decision.of(
                new Feature("Rival leaderboard", "").id(), "Rival leaderboard",
                Features.APPROVED, "")));

        String brief = stateOf("001").getUserFeedback();
        assertEquals(Phase.CONTINUING, stateOf("001").getPhase());
        assertTrue(brief.contains("New feature: Rival leaderboard"), brief);
        assertTrue(brief.contains("None of them exists yet"), brief);
    }

    // ---------------------------------------------------------------- in flight

    /** An agent that holds its turn open until released, so a project stays running. */
    private static final class HeldAgent implements Agent {
        private final AgentKind kind;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        HeldAgent(AgentKind kind) {
            this.kind = kind;
        }

        @Override
        public AgentKind kind() {
            return kind;
        }

        @Override
        public AgentResult run(AgentTask task) {
            calls.incrementAndGet();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("held turn released");
        }
    }

    /** Waits for released turns to finish, so no job thread outlives its test's directory. */
    private void awaitIdle(JobRunner runner, String... ids) throws InterruptedException {
        // Waits for the threads, not for the queue. A project leaves isRunning before its report is
        // written, and a fixed grace period after that was a guess: it stopped covering the report
        // once each git call started watching its process tree, and JUnit found files still being
        // written into the directory it was deleting.
        for (int i = 0; i < 400 && !runner.settled(); i++) {
            Thread.sleep(25);
        }
    }

    private JobRunner runnerWith(Agent claude, int maxInFlight) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, claude);
        map.put(AgentKind.CODEX, new UnusedAgent(AgentKind.CODEX));
        return new JobRunner(home, new Agents(map), maxInFlight, quota);
    }

    @Test
    @DisplayName("a project counts as running the moment Start returns, not when its thread begins")
    void startRegistersImmediately() throws InterruptedException {
        // Registration used to be the first line the pool thread ran, so for a moment after
        // "started" nothing knew — and a second click could put two orchestrators on one repo.
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);
        spec("001", "Alpha", 1, "claude", null);

        assertEquals("started", runner.start("001"));
        assertTrue(runner.isRunning("001"), "Start returned before the project was registered");
        assertEquals("already running", runner.start("001"));

        held.release.countDown();
        awaitIdle(runner, "001");
    }

    @Test
    @DisplayName("a stop that gives up waiting leaves Forge working, not half shut down")
    void unsettledQuiesceResumesWork() throws Exception {
        // redeploy.bat reports "Forge is still running" when a turn will not finish in time. It was
        // running, but still draining: it never filled capacity again and refused every Start as
        // "shutting down", until someone noticed and restarted it by hand.
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "claude", null);
        runner.start("001");
        for (int i = 0; i < 200 && held.calls.get() == 0; i++) {
            Thread.sleep(25);
        }

        Quiesce quiesce = runner.quiesce(Duration.ofMillis(100));

        assertFalse(quiesce.settled(), quiesce.describe());
        String message = runner.start("002");
        held.release.countDown();
        held.release.countDown();
        awaitIdle(runner, "001", "002");
        assertFalse(message.contains("shutting down"), message);
    }

    @Test
    @DisplayName("a stop waits for a request for designs, which has no turn to stop between")
    void quiesceWaitsForAButtonJob() throws Exception {
        // The project is DONE throughout, so a stop that only looked at phases went straight through
        // the middle of the request and killed the vendor calls it was waiting on.
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, held);
        JobRunner runner = new JobRunner(home, new Agents(map), 4, quota);
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        String asked = runner.askForDesigns("001", "", "", "");
        for (int i = 0; i < 200 && held.calls.get() == 0; i++) {
            Thread.sleep(25);
        }

        try {
            assertEquals("asking 1 model for designs", asked);
            Quiesce quiesce = runner.quiesce(Duration.ofMillis(100));

            assertFalse(quiesce.settled(), "a stop went straight through a request for designs");
            assertEquals(List.of("001"), quiesce.stillWorking());
        } finally {
            held.release.countDown();
            awaitIdle(runner, "001");
        }
    }

    @Test
    @DisplayName("with every model out of quota, nobody is asked for designs and the page is told why")
    void noDesignsWhenEveryModelIsClosed() {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, new UnusedAgent(AgentKind.CLAUDE));
        map.put(AgentKind.CODEX, new UnusedAgent(AgentKind.CODEX));
        JobRunner runner = new JobRunner(home, new Agents(map, null, quota), 4, quota);
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        quota.record(AgentKind.CLAUDE, Instant.now().plusSeconds(3600), "not during a test");
        quota.record(AgentKind.CODEX, Instant.now().plusSeconds(3600), "not during a test");

        assertEquals("every model is out of quota at the moment, so there is nobody to ask",
                runner.askForDesigns("001", "", "", ""));
        assertFalse(runner.isRunning("001"));
    }

    @Test
    @DisplayName("an unreadable state file during a stop is counted as work, not thrown")
    void quiesceSurvivesAnUnreadableStateFile() throws Exception {
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);
        spec("001", "Alpha", 1, "claude", null);
        runner.start("001");
        for (int i = 0; i < 200 && held.calls.get() == 0; i++) {
            Thread.sleep(25);
        }
        Path state = RunPaths.under(home.runs(), "001").stateFile();
        String original = Files.readString(state);
        Files.writeString(state, "{\"id\": \"00");

        try {
            Quiesce quiesce = runner.quiesce(Duration.ofMillis(100));

            assertFalse(quiesce.settled());
            assertEquals(List.of("001"), quiesce.stillWorking());
        } finally {
            Files.writeString(state, original);
            held.release.countDown();
            awaitIdle(runner, "001");
        }
    }

    @Test
    @DisplayName("one pass of the queue never launches past the cap")
    void capacityHoldsWithinOnePass() throws Exception {
        // fillCapacity checks the cap inside the loop that launches. With late registration every
        // launch in that loop saw a count of zero, so the cap was a suggestion.
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 2);
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "claude", null);
        spec("003", "Gamma", 3, "claude", null);

        runner.setAutoRun(true);

        long inFlight = Stream.of("001", "002", "003")
                .filter(runner::isRunning).count();
        assertEquals(2, inFlight, "the cap is 2");

        runner.setAutoRun(false);
        held.release.countDown();
        awaitIdle(runner, "001", "002", "003");
    }

    @Test
    @DisplayName("a step that throws holds the project back instead of relaunching it at once")
    void failuresCoolOffInsteadOfLooping() throws Exception {
        // An exception was treated like a stop: slot freed, fillCapacity run, project still
        // eligible in the same phase, launched again — as fast as it could fail, for ever.
        HeldAgent failing = new HeldAgent(AgentKind.CLAUDE);
        failing.release.countDown();
        JobRunner runner = runnerWith(failing, 4);
        spec("001", "Alpha", 1, "claude", null);

        runner.setAutoRun(true);
        for (int i = 0; i < 100 && (runner.isRunning("001") || failing.calls.get() == 0); i++) {
            Thread.sleep(50);
        }
        Thread.sleep(300);

        assertEquals(1, failing.calls.get(), "the failing project was relaunched straight away");
        RunState state = stateOf("001");
        assertNotNull(state.getCoolOffUntil(), "nothing holds it back");
        assertTrue(state.getLastError().contains("will try again"),
                "the card must say something went wrong: " + state.getLastError());
    }

    @Test
    @DisplayName("an explicit Start overrides the cool-off, because pressing it means it was fixed")
    void startOverridesTheCoolOff() throws InterruptedException {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.SCAFFOLDED, AgentKind.CLAUDE);
        RunState state = stateOf("001");
        state.setCoolOffUntil(Instant.now().plus(1, ChronoUnit.HOURS));
        RunStore.save(RunPaths.under(home.runs(), "001").stateFile(), state);

        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);
        assertEquals("started", runner.start("001"));
        assertNull(stateOf("001").getCoolOffUntil());

        held.release.countDown();
        awaitIdle(runner, "001");
    }

    /** Builds for real in a stub sense: writes an overview, hands off, and remembers its briefs. */
    private static final class RecordingBuilder implements Agent {
        final CountDownLatch firstTurn = new CountDownLatch(1);
        final List<String> briefs = new CopyOnWriteArrayList<>();

        @Override
        public AgentKind kind() {
            return AgentKind.CLAUDE;
        }

        @Override
        public AgentResult run(AgentTask task) {
            briefs.add(task.brief());
            if (briefs.size() == 1) {
                try {
                    firstTurn.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                Files.writeString(task.cwd().resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
                Files.writeString(task.cwd().resolve("Pipeline.java"), """
                        /** Reads the input, transforms it, writes the result, in that order. */
                        public final class Pipeline {
                            public void run(java.nio.file.Path in, java.nio.file.Path out) {
                                var rows = reader.read(in);
                                writer.write(transformer.apply(rows), out);
                            }
                        }
                        """);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String handoff = """
                    Done.
                    {"status":"complete","summary":"built","completed":["all"],"remaining":[],
                     "overview":"Pipeline.java","not_verified":["the draw branch is untested"]}
                    """;
            return new AgentResult(AgentOutcome.COMPLETED, handoff,
                    new ProcResult(0, handoff, "", false,
                            Duration.ofSeconds(1)), null);
        }
    }

    /** Reads the diff and finds nothing. */
    private record CleanReviewer(AgentKind kind) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            String reply = "{\"findings\":[]}";
            return new AgentResult(AgentOutcome.COMPLETED, reply,
                    new ProcResult(0, reply, "", false,
                            Duration.ofSeconds(1)), null);
        }
    }

    @Test
    @DisplayName("feedback left during a session reaches the next one when it finishes")
    void midSessionFeedbackIsDelivered() throws Exception {
        // The form said "it will be picked up when that stops", and nothing did: the requests sat on
        // disk until somebody happened to submit a second time. Reported from use.
        spec("001", "Alpha", 1, "claude", null);
        RecordingBuilder builder = new RecordingBuilder();
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, builder);
        map.put(AgentKind.CODEX, new CleanReviewer(AgentKind.CODEX));
        JobRunner runner = new JobRunner(home, new Agents(map), 4, quota);

        runner.start("001");
        for (int i = 0; i < 100 && builder.briefs.isEmpty(); i++) {
            Thread.sleep(50);
        }
        String text = "the draw branch is untested";
        String message = runner.decide("001",
                List.of(sendBack(ReviewItem.of(
                        "unproven", ReviewItem.NOT_VERIFIED, text).id(),
                        text, "render the draw branch and screenshot it")),
                List.of());
        assertTrue(message.contains("acted on when it finishes"), message);

        builder.firstTurn.countDown();
        for (int i = 0; i < 400 && (builder.briefs.size() < 2 || runner.isRunning("001")); i++) {
            Thread.sleep(50);
        }

        assertEquals(2, builder.briefs.size(), "the finished project was never sent back");
        assertTrue(builder.briefs.get(1).contains("render the draw branch and screenshot it"),
                "the second session was not told what was asked:\n" + builder.briefs.get(1));
        assertFalse(Files.exists(RunPaths.under(home.runs(), "001").pendingFeedbackFile()));
        assertEquals(Phase.DONE, stateOf("001").getPhase(),
                "an old submission must never accept work nobody has read");

        // The job thread still writes the report after it deregisters; let it finish before the
        // temporary directory is deleted out from under it.
        Thread.sleep(1500);
    }

    @Test
    @DisplayName("one unreadable state file costs that project its card, not the dashboard or the queue")
    void unreadableStateIsContained() throws Exception {
        // It used to throw straight through the list and the queue's sort: every card vanished,
        // and no project anywhere could start, over one hand-edited file.
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "claude", null);
        Path broken = RunPaths.under(home.runs(), "001").stateFile();
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "{\"id\":\"001\",\"phase\":\"GATI");

        List<JobStatus> rows = jobs.statuses();
        assertEquals(2, rows.size(), "the list failed instead of containing the damage");
        assertTrue(statusOf("001").progress().getBlockedReason().contains("cannot be read"),
                statusOf("001").progress().getBlockedReason());

        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);
        runner.setAutoRun(true);

        assertTrue(runner.isRunning("002"), "a broken neighbour stopped the queue");
        assertFalse(runner.isRunning("001"));
        assertEquals("{\"id\":\"001\",\"phase\":\"GATI", Files.readString(broken),
                "the broken project was recreated over its own history");

        runner.setAutoRun(false);
        held.release.countDown();
        awaitIdle(runner, "002");
    }

    @Test
    @DisplayName("a state file saved from Notepad, with a byte-order mark, still loads")
    void stateWithABomLoads() throws IOException {
        runIn("001", Phase.DONE, AgentKind.CLAUDE);
        Path file = RunPaths.under(home.runs(), "001").stateFile();
        Files.writeString(file, "﻿" + Files.readString(file));

        assertEquals(Phase.DONE, RunStore.load(file).orElseThrow().getPhase());
    }

    // ---------------------------------------------------------------- shutting down

    @Test
    @DisplayName("an idle Forge is safe to stop immediately")
    void quiesceSettlesWhenNothingIsRunning() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE);

        Quiesce quiesce = jobs.quiesce(Duration.ofSeconds(1));

        assertTrue(quiesce.settled(), quiesce.describe());
        assertTrue(quiesce.stillWorking().isEmpty());
    }

    @Test
    @DisplayName("a project being planned is mid-turn, so a stop waits for it")
    void quiesceWaitsForAPlanningTurn() throws Exception {
        // The planning turn writes nothing, so turnInFlight is never set for it — and it is the
        // most expensive call the project makes, on the top of the vendor's ladder. Stopping
        // through the middle of one orphans that CLI and throws the call away.
        spec("001", "Alpha", 1, "claude", null);
        Files.delete(RunPaths.under(home.runs(), "001").planFile());
        HeldAgent held = new HeldAgent(AgentKind.CLAUDE);
        JobRunner runner = runnerWith(held, 4);

        runner.start("001");
        for (int i = 0; i < 200 && held.calls.get() == 0; i++) {
            Thread.sleep(25);
        }

        Quiesce quiesce = runner.quiesce(Duration.ofMillis(100));

        assertFalse(quiesce.settled(), quiesce.describe());
        assertEquals(List.of("001"), quiesce.stillWorking());
        assertEquals(Phase.PLANNING, stateOf("001").getPhase(), "it is the planning turn that is in flight");

        held.release.countDown();
        awaitIdle(runner, "001");
    }

    @Test
    @DisplayName("once asked to stop, it starts nothing new")
    void quiesceRefusesToStartMoreWork() {
        // Otherwise a build could free the jar, and the auto-run tick could start a fresh agent
        // into the two seconds before the process actually exits.
        spec("001", "Alpha", 1, "claude", null);
        jobs.quiesce(Duration.ofSeconds(1));

        String message = jobs.start("001");

        assertTrue(message.contains("shutting down"), message);
    }

    @Test
    @DisplayName("stopping does not turn the queue off for next time")
    void quiesceLeavesTheAutoRunSettingAlone() {
        // Shutting down by flipping the dashboard's own switch would mean every rebuild quietly
        // disabled the queue, and the next start would sit idle until somebody noticed.
        jobs.setAutoRun(true);
        jobs.quiesce(Duration.ofSeconds(1));

        assertTrue(DashboardSettings.load(root).isAutoRun(),
                "the persisted setting must survive a shutdown");
    }

    @Test
    @DisplayName("nothing submitted is not an approval of everything")
    void emptySubmissionApprovesNothing() {
        withFeatures("001", "Report export");

        assertEquals("nothing was submitted", jobs.decide("001", List.of(), List.of()));
        assertEquals(Phase.DONE, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("what you decided is kept, so the report is not a form to fill in twice")
    void decisionsArePersisted() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        jobs.decide("001", List.of(ok("unproven-0", "the draw branch is untested")));

        List<Decision> saved = DecisionStore.load(
                RunPaths.under(home.runs(), "001").decisionsFile());
        assertEquals(1, saved.size());
        assertEquals("unproven-0", saved.get(0).getItemId());
    }

    @Test
    @DisplayName("a project that is not waiting for approval is not decided on")
    void decidingUnfinishedWorkIsRefused() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE, "abc123");

        assertEquals("it is not finished, so there is nothing to accept yet",
                jobs.decide("001", List.of(ok("unproven-0", "something"))));
        assertEquals(Phase.CONTINUING, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("an empty submission changes nothing")
    void emptySubmissionIsRefused() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());

        assertEquals("nothing was submitted", jobs.decide("001", List.of()));
        assertEquals(Phase.DONE, stateOf("001").getPhase());
    }

    // ---------------------------------------------------------------- scheduling

    @Test
    @DisplayName("a project waiting to be reviewed is started before one waiting to be built")
    void reviewsAreStartedFirst() {
        spec("001", "Alpha", 1, "claude", null);
        spec("002", "Beta", 2, "codex", null);
        // 002 outranks nothing: 001 is rank 1. But 001 only needs one read-only turn to finish,
        // and 002 needs many — and on a lopsided pair of plans, the reviewer is the scarce side.
        runIn("001", Phase.REVIEWING, AgentKind.CLAUDE, "abc123");
        runIn("002", Phase.CONTINUING, AgentKind.CODEX, "abc123");

        assertEquals(List.of("001", "002"), startOrder());

        // Now reverse the ranks: the review must still come first.
        spec("001", "Alpha", 9, "claude", null);
        spec("002", "Beta", 1, "codex", null);

        assertEquals(List.of("001", "002"), startOrder(),
                "a cheap turn that finishes a project outranks a dear one that does not");
    }

    @Test
    @DisplayName("a review suspended on quota still counts as a review for ordering")
    void suspendedReviewsKeepTheirPlace() {
        spec("001", "Alpha", 9, "claude", null);
        spec("002", "Beta", 1, "codex", null);

        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState waiting = new RunState();
        waiting.setId("001");
        waiting.setPhase(Phase.SUSPENDED);
        waiting.setResumePhase(Phase.REVIEWING);
        waiting.setAssignedTo(AgentKind.CLAUDE);
        RunStore.save(paths.stateFile(), waiting);
        runIn("002", Phase.CONTINUING, AgentKind.CODEX, "abc123");

        // This is the real shape of the problem: the reviews are all suspended on the scarce
        // vendor's window, so they must be first in line when it reopens.
        assertEquals(List.of("001", "002"), startOrder());
    }

    /** The order fillCapacity would consider, without starting anything. */
    private List<String> startOrder() {
        return jobs.reviewsFirst(Wishlist.load(home.wishlist())).stream()
                .map(Spec::id).toList();
    }

    // ---------------------------------------------------------------- quota

    @Test
    @DisplayName("a project assigned to an exhausted vendor will not start")
    void exhaustedVendorBlocksStart() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE);
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");

        assertTrue(jobs.start("001").contains("out of quota"), "expected a quota refusal");
    }

    @Test
    @DisplayName("the other vendor is unaffected by one vendor's limit")
    void oneVendorsLimitDoesNotBlockTheOther() {
        spec("002", "Beta", 1, "codex", null);
        runIn("002", Phase.CONTINUING, AgentKind.CODEX);
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");

        assertEquals("", statusOf("002").progress().getBlockedReason(),
                "a Codex project should not wait on Claude's quota window");
    }

    @Test
    @DisplayName("an unassigned project waits only when both vendors are out")
    void autoAssignmentNeedsBothVendorsOut() {
        spec("003", "Gamma", 1, "auto", null);
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");

        assertEquals("", statusOf("003").progress().getBlockedReason(), "Codex is still available");

        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");
        assertEquals("both vendors are out of quota", statusOf("003").progress().getBlockedReason());
    }

    @Test
    @DisplayName("an elapsed quota window stops blocking without anything having to clear it")
    void elapsedWindowUnblocks() {
        spec("001", "Alpha", 1, "claude", null);
        quota.record(AgentKind.CLAUDE, Instant.now().minusSeconds(60), "hit your limit");

        assertEquals("", statusOf("001").progress().getBlockedReason());
    }

    // ---------------------------------------------------------------- improvements

    @Test
    @DisplayName("an improvement waits for its parent to be accepted, not merely finished")
    void improvementWaitsForAcceptance() {
        spec("001", "Alpha", 1, "claude", null);
        spec("001.1", "Alpha faster", 2, "claude", "001");

        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE);
        assertEquals("waiting for 001 to be accepted first", statusOf("001.1").progress().getBlockedReason());

        // DONE means the gate passed and it is sitting in front of a person. The improvement is
        // built on top of work nobody has agreed to yet, so it still waits.
        runIn("001", Phase.DONE, AgentKind.CLAUDE);
        assertEquals("waiting for 001 to be accepted first", statusOf("001.1").progress().getBlockedReason());

        runIn("001", Phase.ACCEPTED, AgentKind.CLAUDE);
        assertEquals("", statusOf("001.1").progress().getBlockedReason(),
                "once the parent is accepted the improvement is free to run");
    }

    @Test
    @DisplayName("an improvement naming a parent that is not there says so")
    void orphanedImprovementIsExplained() {
        spec("007.1", "Improve nothing", 1, "claude", "007");

        assertEquals("parent 007 is not in the wishlist", statusOf("007.1").progress().getBlockedReason());
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("a finished project is not restarted by a plain start")
    void terminalProjectsAreNotRestarted() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.ACCEPTED, AgentKind.CLAUDE);

        assertEquals("already ACCEPTED", jobs.start("001"));
    }

    @Test
    @DisplayName("reopening a finished project puts it back to work with its repair budget restored")
    void reopenResetsRepairRounds() {
        spec("001", "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState state = new RunState();
        state.setId("001");
        state.setPhase(Phase.PARKED);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setRepairRounds(6);
        RunStore.save(paths.stateFile(), state);
        // Blocked on quota so the reopened project records its new phase and then waits.
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "hit your limit");

        jobs.reopen("001");

        RunState after = stateOf("001");
        assertEquals(Phase.CONTINUING, after.getPhase());
        assertEquals(0, after.getRepairRounds(),
                "a parked project reopened by hand starts its repair budget again");
        assertNotEquals(Phase.PARKED, after.getPhase());
    }

    @Test
    @DisplayName("stopping something that is not running says so rather than throwing")
    void stoppingIdleProjectIsHarmless() {
        spec("001", "Alpha", 1, "claude", null);

        assertEquals("not running", jobs.stop("001"));
    }

    @Test
    @DisplayName("an unknown id is reported, not ignored")
    void unknownIdsAreReported() {
        assertEquals("no spec with id 999", jobs.start("999"));
        assertEquals("no spec with id 999", jobs.decide("999", List.of()));
    }

    // ---------------------------------------------------------------- cross-review

    /** Puts a review on disk, as the review stage would have left it. */
    private void reviewed(String id, Review review) {
        RunPaths paths = RunPaths.under(home.runs(), id);
        try {
            Files.createDirectories(paths.root());
            Files.writeString(paths.reviewFile(), new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .writeValueAsString(review), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void runIn(String id, Phase phase, AgentKind assigned, String baseline) {
        RunPaths paths = RunPaths.under(home.runs(), id);
        RunState state = new RunState();
        state.setId(id);
        state.setPhase(phase);
        state.setAssignedTo(assigned);
        state.setBaselineCommit(baseline);
        state.setReviewRepairUsed(true);
        RunStore.save(paths.stateFile(), state);
    }

    @Test
    @DisplayName("a security check on demand writes its report and leaves an accepted project accepted")
    void securityCheckOnDemand() throws Exception {
        // The reviewing vendor is out of quota, so the AI layer is skipped and nothing is spent.
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, new UnusedAgent(AgentKind.CLAUDE));
        map.put(AgentKind.CODEX, new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CODEX;
            }

            @Override
            public AgentResult run(AgentTask task) {
                return new AgentResult(AgentOutcome.RATE_LIMITED, "limit",
                        new ProcResult(1, "", "", false, Duration.ZERO), null);
            }
        });
        JobRunner runner = new JobRunner(home, new Agents(map), 4, quota);
        spec("001", "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        Git git = new Git(Files.createDirectories(paths.repo()), paths.gitLog());
        git.init();
        Files.writeString(paths.repo().resolve("Keys.java"),
                "class Keys { String k = \"" + "AKIA" + "Q7XK2M9PLR4TN8WZ" + "\"; }");
        String baseline = git.commitAll("built");
        runIn("001", Phase.ACCEPTED, AgentKind.CLAUDE, baseline);

        assertEquals("security check started", runner.rescan("001"));
        awaitIdle(runner, "001");

        var report = SecurityReport.load(paths.securityFile());
        assertNotNull(report, "no report was written");
        assertEquals(1, report.serious().size(), report.findings().toString());
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase(), "a button must never withdraw an acceptance");
        JobStatus status = runner.statuses().stream()
                .filter(s -> s.identity().getId().equals("001")).findFirst().orElseThrow();
        assertEquals("decide", status.outward().getSecurityState());
        assertEquals(1, status.outward().getSecurityForRuling());
    }

    @Test
    @DisplayName("Deploy runs the spec's command for an accepted, checked project, and the card shows where it went")
    void deployOnRequest() throws Exception {
        Files.writeString(home.wishlist().resolve("001-alpha.md"), """
                ---
                id: "001"
                title: Alpha
                acceptance:
                  - echo ok
                deploy:
                  command: echo Published to https://alpha-42.pages.dev
                  check: exit 0
                ---
                Build it.
                """);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        Git git = new Git(Files.createDirectories(paths.repo()), paths.gitLog());
        git.init();
        Files.writeString(paths.repo().resolve("index.html"), "<h1>alpha</h1>");
        String baseline = git.commitAll("built");
        runIn("001", Phase.DONE, AgentKind.CLAUDE, baseline);

        assertEquals("only an accepted project can be deployed", jobs.deploy("001"));
        assertEquals("only an accepted project can be deployed",
                statusOf("001").outward().getDeployBlockedReason());

        RunState state = stateOf("001");
        state.setPhase(Phase.ACCEPTED);
        RunStore.save(paths.stateFile(), state);
        new SecurityReport(git.head(), git.tree(), List.of()).save(paths.securityFile());
        assertEquals("", statusOf("001").outward().getDeployBlockedReason());

        assertEquals("deploy started", jobs.deploy("001"));
        awaitIdle(jobs, "001");

        StatusOutward outward = statusOf("001").outward();
        assertEquals("deployed", outward.getDeployState());
        assertEquals("https://alpha-42.pages.dev", outward.getDeployUrl());
        assertEquals(git.head(), outward.getDeployedCommit());
        assertFalse(outward.isDeployStale());
        assertTrue(Files.readString(paths.reportFile()).contains("alpha-42.pages.dev"),
                "the report lists the deploy");
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase(), "deploying changes nothing about the project");
    }

    @Test
    @DisplayName("an unfinished project is not checked on demand; its pipeline does that")
    void securityCheckNeedsAFinishedProject() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.GATING, AgentKind.CLAUDE);

        assertTrue(jobs.rescan("001").contains("only a finished project"));
    }

    @Test
    @DisplayName("a finished but unreviewed project can be sent back for a cross-review")
    void rereviewSendsItBack() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", Review.unavailable("reviewer unavailable: RATE_LIMITED", null));
        // CODEX reviews a project CLAUDE built, so closing CODEX's window is what stops this
        // test reaching a vendor once the phase becomes REVIEWING.
        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "still out");

        jobs.rereview("001");

        RunState after = stateOf("001");
        assertEquals(Phase.REVIEWING, after.getPhase());
        assertFalse(after.isReviewRepairUsed(),
                "a review that never ran cannot have spent the repair it was owed");
    }

    @Test
    @DisplayName("a real review's verdict does not hand back a second free repair")
    void rereviewAfterARealReviewKeepsTheBudgetSpent() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");
        reviewed("001", new Review());
        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "still out");

        jobs.rereview("001");
        // The reviewer is out of quota, so nothing was started; only the state moved.

        assertTrue(stateOf("001").isReviewRepairUsed(),
                "the reviewer did look last time, so its repair round was genuinely used");
    }

    @Test
    @DisplayName("an accepted project is not quietly un-accepted by a re-review")
    void rereviewRefusesToWithdrawAnAcceptance() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.ACCEPTED, AgentKind.CLAUDE, "abc123");

        assertEquals("already accepted — use Request Changes if you want it looked at again",
                jobs.rereview("001"));
        assertEquals(Phase.ACCEPTED, stateOf("001").getPhase());
    }

    @Test
    @DisplayName("work in progress is not interrupted to run a review")
    void rereviewRefusesWhileStillWorking() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE, "abc123");

        assertEquals("it is still working; a review runs once it stops", jobs.rereview("001"));
    }

    @Test
    @DisplayName("a project with no baseline has nothing to diff against")
    void rereviewNeedsABaseline() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, null);

        assertEquals("it has no baseline to compare against", jobs.rereview("001"));
    }

    @Test
    @DisplayName("a project waiting to be reviewed waits on the reviewer's quota, not the builder's")
    void reviewingWaitsOnTheReviewingVendor() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.REVIEWING, AgentKind.CLAUDE, "abc123");

        // CLAUDE built it and has plenty left; the next call goes to CODEX, which has none.
        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "out");
        assertTrue(statusOf("001").progress().getBlockedReason().contains("CODEX"),
                "starting here would spend a call to discover a limit already recorded");

        quota.clear(AgentKind.CODEX);
        quota.record(AgentKind.CLAUDE, Instant.now().plus(2, ChronoUnit.HOURS), "out");
        assertEquals("", statusOf("001").progress().getBlockedReason(),
                "the builder's window is irrelevant to a review it will not take part in");
    }

    @Test
    @DisplayName("the card is told how much a passing gate did not prove")
    void notVerifiedCountIsReported() {
        spec("001", "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState state = new RunState();
        state.setId("001");
        state.setPhase(Phase.DONE);
        state.setAssignedTo(AgentKind.CLAUDE);

        Plan plan = new Plan();
        plan.setSummary("built it");
        plan.setNotVerified(List.of("the draw branch has never been rendered",
                "keyboard controls are untested"));
        state.setPlan(plan);
        RunStore.save(paths.stateFile(), state);

        // Approval happens on the card, so the caveats have to reach the card. Leaving them only
        // in the report means accepting without them is the path of least resistance.
        assertEquals(2, statusOf("001").handoff().getNotVerifiedCount());
    }

    @Test
    @DisplayName("a project suspended awaiting review waits on the reviewer, not the builder")
    void suspendedReviewChecksTheReviewer() {
        spec("001", "Alpha", 1, "claude", null);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        RunState state = new RunState();
        state.setId("001");
        state.setPhase(Phase.SUSPENDED);
        state.setResumePhase(Phase.REVIEWING);
        state.setAssignedTo(AgentKind.CLAUDE);
        state.setBaselineCommit("abc123");
        RunStore.save(paths.stateFile(), state);

        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "out");

        // The bug this pins: only phase was consulted, so a project suspended waiting to be
        // reviewed was measured against its builder's quota. It looked runnable, started, spent a
        // call finding out the reviewer was out, and suspended again — once per project, per pass.
        assertTrue(statusOf("001").progress().getBlockedReason().contains("CODEX"),
                "expected the reviewer's window to block it, got: "
                        + statusOf("001").progress().getBlockedReason());
    }

    @Test
    @DisplayName("the dashboard says when a cross-review cannot run, before it is offered")
    void reviewBlockedReasonIsReported() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");

        assertEquals("", statusOf("001").review().getReviewBlockedReason());

        quota.record(AgentKind.CODEX, Instant.now().plus(2, ChronoUnit.HOURS), "out");
        assertTrue(statusOf("001").review().getReviewBlockedReason().contains("CODEX"),
                "a button certain to be refused should say so instead");
    }

    @Test
    @DisplayName("the dashboard is told when nothing has reviewed the project")
    void reviewStateIsReported() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE, "abc123");

        assertEquals("none", statusOf("001").review().getReviewState());

        reviewed("001", Review.unavailable("RATE_LIMITED", null));
        assertEquals("unavailable", statusOf("001").review().getReviewState());

        // The shape written before the unavailable flag existed: a parse error and nothing else.
        reviewed("001", Review.failed("reviewer unavailable: RATE_LIMITED"));
        assertEquals("unavailable", statusOf("001").review().getReviewState(),
                "an older run that was never reviewed must still say so");

        reviewed("001", new Review());
        assertEquals("clean", statusOf("001").review().getReviewState());
    }

    // ---------------------------------------------------------------- enum drift

    /**
     * Every phase, through every control the dashboard offers.
     *
     * <p>This is here because of a real bug: {@code Phase.ACCEPTED} was added for the approval loop
     * and {@code parentAccepted} kept comparing against {@code DONE}, so accepting a project made
     * its improvements permanently ineligible. Nothing failed — 114 tests passed — because no test
     * enumerated the phases. A parameterised sweep costs one method and turns "someone added a
     * constant and missed a branch" from a silent behaviour change into a build failure.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Phase.class)
    @DisplayName("every phase has a defined answer for every control")
    void everyPhaseIsHandled(Phase phase) {
        spec("001", "Alpha", 1, "claude", null);
        spec("001.1", "Alpha faster", 2, "claude", "001");
        runIn("001", phase, AgentKind.CLAUDE);
        // Both windows closed, so every control below stops at a decision instead of submitting
        // work. Without this, requestChanges on a terminal phase really does launch a job, which
        // then scaffolds a repository into the temp directory while JUnit is deleting it.
        Instant later = Instant.now().plus(2, ChronoUnit.HOURS);
        quota.record(AgentKind.CLAUDE, later, "not during a test");
        quota.record(AgentKind.CODEX, later, "not during a test");

        JobStatus status = statusOf("001");
        assertNotNull(status.progress().getPhaseLabel(), phase + " has no label for the status bar");
        assertFalse(status.progress().getPhaseLabel().isBlank(), phase + " has a blank label");
        assertTrue(status.progress().getPercent() >= 0 && status.progress().getPercent() <= 100,
                phase + " reports a progress of " + status.progress().getPercent() + "%");

        // None of these may throw, whatever state the project is in — the dashboard offers them
        // on every card and a person can click any of them at any time.
        assertNotNull(jobs.decide("001", List.of(ok("x", "an item"))));
        assertNotNull(jobs.rereview("001"));
        assertNotNull(jobs.stop("001"));
        assertNotNull(statusOf("001.1").progress().getBlockedReason(),
                phase + " leaves the improvement with no explanation of why it is waiting");
    }

    @Test
    @DisplayName("a restart resumes nothing until the queue has been switched on")
    void startupResumesNothingByDefault() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE, "abc123");

        assertEquals(List.of(), jobs.resumeQueue());
        assertFalse(jobs.isAutoRun());
    }

    @Test
    @DisplayName("a restart picks the queue back up once it has been switched on")
    void startupResumesWhenEnabled() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.CONTINUING, AgentKind.CLAUDE, "abc123");
        // Both vendors closed, so the pass reaches its decision and starts nothing — this test is
        // about the setting being restored, not about spending quota.
        Instant later = Instant.now().plus(2, ChronoUnit.HOURS);
        quota.record(AgentKind.CLAUDE, later, "not during a test");
        quota.record(AgentKind.CODEX, later, "not during a test");
        new DashboardSettings(true).save(root);

        jobs.resumeQueue();

        assertTrue(jobs.isAutoRun(), "the setting has to come back, or the queue stops again");
    }

    @Test
    @DisplayName("switching the queue on is remembered for next time")
    void autoRunIsPersisted() {
        jobs.setAutoRun(true);

        assertTrue(DashboardSettings.load(root).isAutoRun());

        jobs.setAutoRun(false);
        assertFalse(DashboardSettings.load(root).isAutoRun());
    }

    @Test
    @DisplayName("auto-run is off until it is switched on")
    void autoRunDefaultsOff() {
        assertTrue(!jobs.isAutoRun(), "agents must not start because a browser tab was opened");

        jobs.setAutoRun(true);
        assertTrue(jobs.isAutoRun());
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

    @Test
    @DisplayName("a pasted screenshot is kept in the project for its agent, and out of its history")
    void aScreenshotIsKeptWhereTheAgentCanOpenItAndOutOfGit() throws IOException {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE);
        RunPaths paths = RunPaths.under(home.runs(), "001");
        Files.createDirectories(paths.repo());
        Files.createDirectories(paths.gitLog().getParent());
        Git git = new Git(paths.repo(), paths.gitLog());
        git.init();
        Files.writeString(paths.repo().resolve("README.md"), "alpha");
        git.commitAll("start");

        String reference = jobs.attach("001", PNG);

        assertTrue(reference.matches("\\[image: \\.forge-images/\\d{8}-\\d{6}-1\\.png]"), reference);
        String path = reference.substring("[image: ".length(), reference.length() - 1);
        assertTrue(Files.isRegularFile(paths.repo().resolve(path)), "saved inside the repository the agent works in");
        git.commitAll("a turn");
        assertTrue(git.files().stream().noneMatch(f -> f.startsWith(".forge-images")),
                "a screenshot must never land in the project's history or its review diff: " + git.files());

        assertNotEquals(reference, jobs.attach("001", PNG), "a second picture never overwrites the first");
    }

    @Test
    @DisplayName("only an image is accepted as one, whatever it claims to be")
    void onlyImagesAreAttached() {
        spec("001", "Alpha", 1, "claude", null);
        runIn("001", Phase.DONE, AgentKind.CLAUDE);

        IllegalArgumentException text = assertThrows(IllegalArgumentException.class,
                () -> jobs.attach("001", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(text.getMessage().contains("PNG, JPEG, GIF or WebP"), text.getMessage());
        assertThrows(IllegalArgumentException.class, () -> jobs.attach("001", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> jobs.attach("999", PNG));
        assertTrue(jobs.attach("001", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}).endsWith(".jpg]"));
        assertTrue(jobs.attach("001", "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)).endsWith(".webp]"));
    }
}
