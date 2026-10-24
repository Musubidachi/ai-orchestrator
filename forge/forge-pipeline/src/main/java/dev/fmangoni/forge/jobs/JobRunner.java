package dev.fmangoni.forge.jobs;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.jobs.button.DeployJob;
import dev.fmangoni.forge.jobs.button.MarketingJob;
import dev.fmangoni.forge.jobs.button.Reopening;
import dev.fmangoni.forge.jobs.button.Narration;
import dev.fmangoni.forge.jobs.button.Play;
import dev.fmangoni.forge.jobs.button.SecurityRescan;
import dev.fmangoni.forge.jobs.button.WalkthroughJob;
import dev.fmangoni.forge.jobs.publish.PublishJob;
import dev.fmangoni.forge.jobs.polish.Polish;
import dev.fmangoni.forge.jobs.polish.PolishView;
import dev.fmangoni.forge.jobs.queue.JobQueue;
import dev.fmangoni.forge.jobs.queue.PipelineJobs;
import dev.fmangoni.forge.jobs.queue.StartRules;
import dev.fmangoni.forge.jobs.stop.Quiesce;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.submit.Attachments;
import dev.fmangoni.forge.jobs.submit.ReviewDecks;
import dev.fmangoni.forge.jobs.submit.Submissions;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.LockedElsewhere;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.jobs.work.ReportRefresh;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.deck.DeckView;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.status.JobStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops projects on demand, so the dashboard can override the queue.
 *
 * <p>A facade, and the one lock. Every decision to launch is taken under this object's monitor;
 * the classes behind it are not synchronized themselves:
 * <ul>
 *   <li>{@link JobPool} — the workers in flight, and stopping one;</li>
 *   <li>{@link StartRules} — why a project cannot start;</li>
 *   <li>{@link JobQueue} — auto-run, and what it may start, in what order;</li>
 *   <li>{@link PipelineJobs} — starting a project's pipeline;</li>
 *   <li>{@link DeployJob}, {@link MarketingJob}, {@link SecurityRescan} — the jobs a button starts;</li>
 *   <li>{@link Reopening} — reopen, re-review, and fix blocking findings;</li>
 *   <li>{@link Play} — opening a project's run file;</li>
 *   <li>{@link Polish} — asking the vendors for looks, and building the one picked;</li>
 *   <li>{@link Submissions} — what a submitted review sets off;</li>
 *   <li>{@link ReviewDecks} — the same review, dealt as cards for the quick review page;</li>
 *   <li>{@link Attachments} — screenshots put into a note (starts nothing, so not under the lock);</li>
 *   <li>{@link Shutdown} — stopping without destroying a turn;</li>
 *   <li>{@link StatusBoard} — the dashboard's rows.</li>
 * </ul>
 */
public final class JobRunner {

    private final ForgeHome home;
    private final JobPool pool;
    private final StartRules rules;
    private final JobQueue queue;
    private final Shutdown shutdown;
    private final PipelineJobs pipeline;
    private final Reopening reopening;
    private final DeployJob deploys;
    private final PublishJob publishes;
    private final MarketingJob marketing;
    private final SecurityRescan rescans;
    private final WalkthroughJob walkthroughs;
    private final Play play;
    private final Polish polish;
    private final StatusBoard board;
    private final Submissions submissions;
    private final ReviewDecks decks;
    private final Attachments attachments;

    /**
     * Wakes periodically to pick work up again.
     *
     * <p>Needed for the case nothing else covers: every project suspended on quota, so no job ends
     * to trigger the next start. A minute is frequent enough that a reopened window is used
     * promptly and rare enough to cost nothing while idle.
     */
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "forge-autorun");
        t.setDaemon(true);
        return t;
    });

    public JobRunner(ForgeHome home, Agents agents, int maxInFlight, QuotaLedger quota) {
        this.home = home;
        this.pool = new JobPool(maxInFlight);
        this.rules = new StartRules(home, agents, quota, pool);
        Projects projects = new Projects(home, rules.eligibility());
        LockedElsewhere elsewhere = new LockedElsewhere();
        this.queue = new JobQueue(home, rules, pool, elsewhere);
        this.shutdown = new Shutdown(projects, pool);
        this.pipeline = new PipelineJobs(home, agents, quota, pool, rules, projects, shutdown, elsewhere,
                this::afterJob);
        this.reopening = new Reopening(projects, pool, pipeline);
        this.deploys = new DeployJob(projects, pool, shutdown);
        this.publishes = new PublishJob(projects, pool, shutdown);
        this.marketing = new MarketingJob(projects, pool, shutdown, agents);
        this.rescans = new SecurityRescan(projects, pool, shutdown, agents);
        this.walkthroughs = new WalkthroughJob(projects, pool);
        this.play = new Play(projects, pool);
        this.polish = new Polish(projects, pool, shutdown, agents, pipeline);
        this.board = new StatusBoard(projects, rules, queue, pool);
        this.submissions = new Submissions(projects, pool, rules, pipeline);
        this.decks = new ReviewDecks(projects, home.runs());
        this.attachments = new Attachments(projects);
        ticker.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    ForgeHome home() {
        return home;
    }

    RunPaths pathsFor(Spec spec, Wishlist wishlist) {
        return rules.pathsFor(spec, wishlist);
    }

    boolean awaitingReview(RunState state) {
        return rules.awaitingReview(state);
    }

    List<Spec> reviewsFirst(Wishlist wishlist) {
        return queue.reviewsFirst(wishlist);
    }

    /**
     * Whether every job has finished, including the writing it does after leaving the queue.
     *
     * <p>Public for whoever has to wait for quiet, such as a test about to delete the directory the
     * jobs write into. A project stops showing as running before its report is rewritten, so
     * waiting on that is not enough.
     */
    public boolean settled() {
        return pool.settled();
    }

    /**
     * Turns automatic queue-filling on or off.
     *
     * <p>While on, a tick starts the highest-ranked eligible project whenever a slot is free, so
     * idle capacity does not sit next to queued work. It stops on its own when the vendors run out
     * of quota, because a rate-limited turn suspends with a reset time and nothing is eligible
     * again until that passes.
     *
     * <p>Forge cannot see how much quota is left before a vendor says so, so it cannot hold back
     * before running out. What it can do is keep working until a vendor says stop, then wait
     * exactly as long as that vendor asked.
     */
    public void setAutoRun(boolean enabled) {
        queue.setAutoRun(enabled);
        if (enabled) {
            fillCapacity();
        }
    }

    public boolean isAutoRun() {
        return queue.isAutoRun();
    }

    /**
     * Picks work back up when the editor starts, in the order the queue would have chosen.
     *
     * <p>The editor is restarted constantly — every rebuild is a restart — and each one used to
     * leave every project sitting still until someone re-ticked a box. Anything already suspended
     * on a quota window, already finished, or waiting on its parent stays where it is: this only
     * resumes what a running dashboard would have started anyway. And only when the setting says so.
     *
     * @return what it started, for the launch output to name
     */
    public synchronized List<String> resumeQueue() {
        if (!queue.resumeSetting()) {
            return List.of();
        }
        List<String> before = List.copyOf(pool.ids());
        fillCapacity();
        return pool.ids().stream().filter(id -> !before.contains(id)).sorted().toList();
    }

    /** Starts as many eligible projects as there is room for, reviews first, then by rank. */
    public synchronized void fillCapacity() {
        if (!queue.isAutoRun() || shutdown.isDraining()) {
            return;
        }
        Wishlist wishlist = Wishlist.load(home.wishlist());
        for (Spec spec : queue.reviewsFirst(wishlist)) {
            if (pool.full()) {
                return;
            }
            if (queue.eligible(spec, wishlist)) {
                pipeline.launch(spec, wishlist, false);
            }
        }
    }

    private void tick() {
        try {
            fillCapacity();
        } catch (RuntimeException e) {
            System.err.println("auto-run tick failed: " + e.getMessage());
        }
    }

    /** What follows a pipeline job leaving the pool. */
    private void afterJob(String id, RunPaths paths) {
        ReportRefresh.write(paths, null);
        // Before filling capacity, so a project reopened by its own feedback is started by that
        // rather than waiting behind whatever the queue would have picked next.
        try {
            synchronized (this) {
                submissions.deliverPending(id, paths);
            }
        } catch (RuntimeException e) {
            System.err.println("[" + id + "] could not act on pending feedback: " + e);
        }
        // A slot just freed. Checking here rather than only on the timer means the next project
        // starts immediately instead of waiting out the remainder of a tick.
        fillCapacity();
    }

    /** See {@link PipelineJobs#start}. */
    public synchronized String start(String id) {
        return pipeline.start(id);
    }

    /** See {@link Reopening#reopen}. */
    public synchronized String reopen(String id) {
        return reopening.reopen(id);
    }

    /** See {@link Reopening#rereview}. */
    public synchronized String rereview(String id) {
        return reopening.rereview(id);
    }

    /** See {@link Reopening#fixBlocking}. */
    public synchronized String fixBlocking(String id) {
        return reopening.fixBlocking(id);
    }

    /** See {@link Reopening#recheck}. */
    public synchronized String recheck(String id) {
        return reopening.recheck(id);
    }

    /** See {@link Attachments#save}. Starts nothing, so it takes no lock. */
    public String attach(String id, byte[] image) {
        return attachments.save(id, image);
    }

    /** See {@link Submissions#ruleFeature}. */
    public synchronized String ruleFeature(String id, String itemId, String verdict, String sameAs) {
        return submissions.ruleFeature(id, itemId, verdict, sameAs);
    }

    /** See {@link Submissions#ruleFeature}, with what the person wants changed about it. */
    public synchronized String ruleFeature(String id, String itemId, String verdict, String sameAs, String note) {
        return submissions.ruleFeature(id, itemId, verdict, sameAs, note);
    }

    /** Records what you decided about each outstanding item, and does whatever follows. See {@link Submissions}. */
    public synchronized String decide(String id, List<Decision> decisions) {
        return decide(id, decisions, List.of());
    }

    public synchronized String decide(String id, List<Decision> decisions, List<Decision> features) {
        boolean wasApproved = phaseOf(id) == Phase.ACCEPTED;
        String result = submissions.decide(id, decisions, features);
        // Approving a version is what puts it on GitHub: the person asked for those to be one act.
        if (!wasApproved && phaseOf(id) == Phase.ACCEPTED && publishes.enabled()) {
            return result + "; " + publishes.start(id);
        }
        return result;
    }

    /** See {@link DeployJob}. */
    public synchronized String deploy(String id) {
        return deploys.start(id);
    }

    /** See {@link Reopening#approveLook}. */
    public synchronized String approveLook(String id) {
        return reopening.approveLook(id);
    }

    /** See {@link PublishJob}: Publish, pressed to try again or to put up versions approved before it existed. */
    public synchronized String publish(String id) {
        return publishes.start(id);
    }

    private Phase phaseOf(String id) {
        return RunStore.survey(RunPaths.under(home.runs(), id).stateFile()).map(RunState::getPhase).orElse(null);
    }

    /** See {@link MarketingJob}. */
    public synchronized String marketing(String id) {
        return marketing.start(id);
    }

    /** See {@link SecurityRescan}. */
    public synchronized String rescan(String id) {
        return rescans.start(id);
    }

    /**
     * Says what a project is, for the page to read aloud. See {@link WalkthroughJob}.
     *
     * <p>Synchronized like the other actions, though it starts nothing: it writes the transcript
     * into the run's directory and takes a worker to record it, so it queues behind a job on the
     * same project rather than writing underneath one.
     */
    public synchronized Narration walkthrough(String id) {
        return walkthroughs.speak(id);
    }

    /** See {@link Polish#ask}. */
    public synchronized String askForDesigns(String id, String note, String base, String extra) {
        return polish.ask(id, note, base, extra);
    }

    /** See {@link Polish#build}. */
    public synchronized String buildDesign(String id, String optionId, String note, String extra) {
        return polish.build(id, optionId, note, extra);
    }

    /** See {@link Polish#view}. Reads only, so it takes no lock. */
    public Optional<PolishView> polishView(String id) {
        return polish.view(id);
    }

    /** See {@link ReviewDecks}. Reads only, so it takes no lock: a review can be read while a job runs. */
    public Optional<DeckView> reviewDeck(String id) {
        return decks.of(id);
    }

    /** See {@link Play}. Synchronized so it cannot open a project a Start is in the middle of launching. */
    public synchronized String play(String id) {
        return play.start(id);
    }

    /** See {@link Shutdown}. */
    public Quiesce quiesce(Duration timeout) {
        return shutdown.quiesce(timeout, this::fillCapacity);
    }

    /** See {@link JobPool#stop}. */
    public String stop(String id) {
        return pool.stop(id);
    }

    public boolean isRunning(String id) {
        return pool.isRunning(id);
    }

    /** One row per spec, whether or not it has ever run. */
    public List<JobStatus> statuses() {
        return board.statuses();
    }
}
