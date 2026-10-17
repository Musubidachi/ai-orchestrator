package dev.fmangoni.forge.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.review.model.Finding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The persisted state of one project, rewritten after every phase transition.
 *
 * <p>This is what makes an unattended run survive quota windows and reboots: a suspended project
 * resumes at the exact phase it stopped in rather than starting over and spending quota
 * re-deriving work it already did.
 *
 * <p>Unknown properties are ignored on read so a state file written by an older Forge still loads.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunState {

    private String id;
    private String title;

    @Builder.Default
    private Phase phase = Phase.NEW;

    /**
     * The agent that builds this project; its counterpart reviews.
     *
     * <p>Named {@code assignedTo} rather than {@code builder} deliberately — Lombok generates a
     * static {@code builder()} on this class, and two unrelated meanings of "builder" one line
     * apart is a reading hazard.
     */
    private AgentKind assignedTo;

    /** Commit of the scaffold, before the agent touched anything. The review diffs against this. */
    private String baselineCommit;

    /** Repair rounds spent since the project last started afresh: reopening or polishing resets it. */
    private int repairRounds;

    /**
     * Every repair round the project has ever needed, never reset.
     *
     * <p>{@link #repairRounds} is a budget, and a fresh attempt gets a fresh one. This is the record:
     * the league counted a project as finishing "with no repair round" off the budget, so one that
     * needed three and was later polished scored as though it had passed first time.
     */
    private int repairRoundsEver;

    /** Whether any review-repair turn has run. Kept for the report; {@link #reviewRepairs} decides. */
    private boolean reviewRepairUsed;

    /**
     * Review-repair turns sent since the last fresh review was asked for.
     *
     * <p>The backstop behind the per-finding attempts. Each finding gets its own attempts, but a
     * reviewer raises new findings every round, so a count per finding alone could go on for as long
     * as the reviewer keeps finding things. Reset when a person asks for a fresh review.
     */
    private int reviewRepairs;

    /** Whether the one repair turn serious security findings earn has been spent. */
    private boolean securityRepairUsed;

    /**
     * Set immediately before an agent turn and cleared once its work is committed.
     *
     * <p>This is how an interrupted turn is recognised on the next run. The earlier approach —
     * inferring it from a dirty working tree — was wrong: a gate leaves artefacts of its own
     * ({@code __pycache__}, coverage output, anything a stack produces that the scaffold's
     * gitignore did not anticipate), and mistaking those for interrupted work would commit them
     * and skip the repair turn that was actually owed.
     */
    private boolean turnInFlight;

    /**
     * Set when an interrupted turn's work was recovered, until the gate has judged it.
     *
     * <p>Such a turn left no handoff, so its gate fails on the missing overview whatever it built,
     * and that failure was charged as a repair round. 005 parked on four of them: an agent had
     * stopped every Java process on the machine, Forge with it, and each recovered turn's gate spent
     * a round the agent never had the chance to use. The next turn is still a repair — it has the
     * failure to act on — but uncharged; the session count bounds a project that keeps dying.
     */
    private boolean recoveredTurn;

    /**
     * Set by Check again until the gate has run, because that gate is the whole of the run.
     *
     * <p>A gate that fails after a turn leads to another turn. One asked for on its own exists so a
     * person can see whether a ruling they just made was what stood in the way, without paying for a
     * session; if it still fails, the project parks again where it was.
     */
    private boolean gateOnly;

    /**
     * Ids of the approved features the last gate to reach the recordings check owed a clip.
     *
     * <p>With {@link #unfilmedAtLastGate}, what lets the next gate see a clip that moved from one
     * feature to another, rather than one that was simply made or lost.
     */
    @Builder.Default
    private List<String> owedAtLastGate = new ArrayList<>();

    /** Ids of the approved features that gate found without a usable clip. */
    @Builder.Default
    private List<String> unfilmedAtLastGate = new ArrayList<>();

    /**
     * Ids of missing clips a person said they still want since the last gate.
     *
     * <p>So the dashboard can show the question as answered. Saying "still want it" changes no
     * ruling — the feature was approved already — and a question that looked unanswered after the
     * answer read as a button that did nothing.
     */
    @Builder.Default
    private List<String> keptClips = new ArrayList<>();

    /**
     * The commit a visual polish started from, until its review has been decided.
     *
     * <p>Set when a picked design is sent to be built. The review then asks how much the polish
     * actually changed, from this commit, rather than reading the whole project again: a change to
     * stylesheets and images alone is not cross-reviewed, and one to code is first put to the
     * reviewer's cheapest model as a single question.
     */
    private String polishBase;

    /**
     * The design the person picked for the polish under way, by name, or null when the look was
     * left to the agent. A polish built to a picture is always reviewed in full, against that
     * picture: 003's "Hard-Edged Die-Cast" came back with its detailed cars drawn as flat shapes,
     * was judged appearance-only and skipped, and nobody compared it with the design but the person.
     */
    private String polishDesign;

    /** How the last polish's review was decided, in words for the dashboard, or null. */
    private String polishCheck;

    /**
     * What to tell the next session about a change to the spec, until a session has been told.
     *
     * <p>The repository's copy is brought up to date before a turn, so by the turn after it the
     * difference is gone. A turn refused for quota would otherwise take the only notice with it.
     */
    private String specChange;

    /**
     * Put first in the next brief when the person moved a started project to another vendor: whose
     * work it is taking over, and what it must check for itself. Kept, like {@link #specChange},
     * until a turn reaches a model, and put in front of whatever kind of turn that is: a takeover
     * that lands on a repair turn is a takeover all the same.
     */
    private String takeover;

    /**
     * The acceptance command that last failed, and its output.
     *
     * <p>Persisted rather than held in memory: the repair turn that consumes it may happen in a
     * different process, hours later, after a quota window closed in between.
     */
    private CommandResult lastFailure;

    /** Blocking review findings awaiting a repair turn, for the same reason. */
    @Builder.Default
    private List<Finding> blockingFindings = new ArrayList<>();

    /**
     * The last session's handoff: what it finished, what remains, how many sessions it expects.
     *
     * <p>Fed into the next turn's brief so a project too large for one session continues rather
     * than restarting from the spec and rediscovering the same decisions at full price.
     */
    private Plan plan;

    /**
     * Seconds each acceptance command has taken, most recent last.
     *
     * <p>Kept because the gate measures it anyway, and a command that has quietly gone from twenty
     * seconds to three minutes is the performance regression a person actually wants to hear about.
     * It is a record, never a threshold — nothing here can fail a build.
     */
    private Map<String, List<Integer>> commandSeconds =
            new LinkedHashMap<>();

    /** How many agent turns this project has consumed, across every run and every process. */
    private int sessions;

    /**
     * Tokens this project's build turns have moved, as each vendor reported them.
     *
     * <p>Recorded because the usage ledger is kept per vendor, so "which project ate the window"
     * had no answer anywhere. Totals include cache reads, which dominate a resumed conversation and
     * are what the vendors' windows are measured in. Reviews are not counted; nor is anything
     * before this was added.
     */
    private long tokensSpent;

    /**
     * Milliseconds Forge has actually spent working on this project, summed across every step.
     *
     * <p>Accumulated in milliseconds and shown in seconds. Rounding each step to whole seconds
     * first would silently discard every sub-second one, and a project is dozens of steps.
     *
     * <p>Accumulated rather than derived from {@link #createdAt}, because the two are wildly
     * different numbers and only this one is about the project. A project blocked on quota at
     * nine in the evening has the same claim on wall-clock time by morning as one that ran all
     * night, and the dashboard used to report both as "working for" — a figure that grew while
     * nothing whatsoever was happening.
     *
     * <p>Counted around each step of the state machine, so it covers agent turns, the gate and the
     * review, and covers nothing else. Time queued, suspended, or waiting for a person is not work
     * and is not counted here.
     */
    private long workedMillis;

    /**
     * Auto-run leaves the project alone until this passes, because its last step threw.
     *
     * <p>Separate from {@link #retryAfter}, which belongs to a vendor's quota window and is only
     * read while suspended. This is about Forge failing, in whatever phase that happened, and a
     * suspension would have changed the phase the project resumes into.
     */
    private Instant coolOffUntil;

    /**
     * Why this project is waiting for a person, or null when it is not.
     *
     * <p>Set when trying again on a timer could only repeat what just happened: a CLI that is not
     * logged in, a CLI whose own sandbox will not start a process, or the same failure several
     * times running. Neither auto-run nor {@code forge run} starts a project while this is set, and
     * only an explicit Start clears it — the person saying they have dealt with it.
     *
     * <p>Exists because every one of those used to be retried: a broken sandbox relaunched a full
     * agent turn the moment the last one ended, and a gate that threw on a handoff it could not
     * read re-ran its whole suite every twelve minutes for three days.
     */
    private String needsPerson;

    /**
     * Agent calls in a row that failed without doing anything, reset by one that does.
     *
     * <p>Not {@link #sessions}: a refused call reached no model and did no work, and charging it to
     * the session budget parks a healthy project for its vendor's behaviour. Counted separately so
     * a CLI that fails the same way every time still ends up in front of a person.
     */
    private int failedCallsInARow;

    /** Times in a row Forge itself threw while advancing this project, reset by a clean pass. */
    private int errorsInARow;

    /**
     * Turns spent on the current vendor conversation, reset whenever one is dropped.
     *
     * <p>Separate from {@link #sessions} because they answer different questions: that one is how
     * much work the project has taken, this one is how much history the next call would carry.
     */
    private int sessionsOnConversation;

    /** How many times the conversation has been dropped and restarted from the digest. */
    private int compactions;

    /** Turns spent continuing unfinished work rather than fixing something broken. */
    private int continuations;

    /**
     * Why you declined it, carried into the next turn's brief.
     *
     * <p>Outranks anything the reviewing model said. A model can be argued with; this is the person
     * the work is for telling it what is actually wrong, and it is cleared once delivered so the
     * turn after does not act on it twice.
     */
    private String userFeedback;

    /** HEAD before the last agent turn, so progress can be told from spinning. */
    private String preTurnCommit;

    /** Live agent sessions, one per vendor, resumed on the next turn to keep the model's context. */
    private String claudeSession;
    private String codexSession;

    /** Why the last attempt failed, carried into the report so a PARKED project explains itself. */
    private String lastError;

    /** Set when SUSPENDED on quota: the earliest time it is worth trying again. */
    private Instant retryAfter;

    /**
     * The phase to re-enter when a suspended run resumes.
     *
     * <p>Without this, {@link Phase#SUSPENDED} would lose the distinction between a project that
     * stalled before its first build and one that stalled halfway through repairs — and resuming
     * the wrong one either repeats work already paid for or skips work never done.
     */
    private Phase resumePhase;

    private Instant createdAt;
    private Instant updatedAt;
}
