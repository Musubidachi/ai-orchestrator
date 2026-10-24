package dev.fmangoni.forge.jobs.queue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.model.QuotaWindow;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.policy.Reviewers;
import dev.fmangoni.forge.jobs.work.Eligibility;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Why a project cannot start right now, worded for the card that shows it.
 *
 * <p>Built on {@link Eligibility}, which the unattended {@code Scheduler} shares, so the two cannot
 * disagree about what is runnable. What this adds is what only a running dashboard knows: which
 * projects are in flight, and which vendor a waiting project would actually call.
 */
public final class StartRules {

    private final ForgeHome home;
    private final Agents agents;

    /** Vendor-level quota state, so one project's limit stops every project on that vendor. */
    private final QuotaLedger quota;

    private final JobPool pool;

    public StartRules(ForgeHome home, Agents agents, QuotaLedger quota, JobPool pool) {
        this.home = home;
        this.agents = agents;
        this.quota = quota;
        this.pool = pool;
    }

    public Eligibility eligibility() {
        return new Eligibility(home.runs(), agents, quota);
    }

    /** Delegates, so the two schedulers cannot disagree about where a project's files are. */
    public RunPaths pathsFor(Spec spec, Wishlist wishlist) {
        return eligibility().pathsFor(spec, wishlist);
    }

    /** Whether the next call on this spec's project goes to a reviewer rather than its builder. */
    public boolean awaitingReview(Spec spec, Wishlist wishlist) {
        // Survey: used as a sort key for the whole queue, so a throw here stopped every project.
        RunState state = RunStore.survey(pathsFor(spec, wishlist).stateFile()).orElse(null);
        return state != null && awaitingReview(state);
    }

    /** One definition of "waiting on a reviewer", shared with the unattended pass. */
    public boolean awaitingReview(RunState state) {
        return eligibility().awaitingReview(state);
    }

    /**
     * Why this spec cannot start right now, or {@code null} if it can.
     *
     * <p>Both constraints are structural rather than policy. An improvement to a project that is
     * not finished has nothing to improve. And two jobs sharing a repository would be editing one
     * working tree at the same time, so improvements to a project queue behind each other.
     */
    public String blockedReason(Spec spec, Wishlist wishlist) {
        // Before anything else, finished projects included: with its repository not here, shelved or
        // gone, whatever started it would work in an empty folder or build it again from nothing.
        String notHere = eligibility().notHere(spec);
        if (notHere != null) {
            return notHere;
        }
        if (finished(spec)) {
            return null;
        }
        if (wishlist.isOrphan(spec)) {
            return "parent " + spec.parentId().orElse("?") + " is not in the wishlist";
        }
        if (!eligibility().parentAccepted(spec)) {
            return "waiting for " + spec.parentId().orElse("?") + " to be accepted first";
        }
        String familyWait = eligibility().familyWait(spec);
        if (familyWait != null) {
            return familyWait;
        }
        String vendorWait = vendorBlockedReason(spec, wishlist);
        if (vendorWait != null) {
            return vendorWait;
        }
        return sharedRepository(spec, wishlist);
    }

    private String sharedRepository(Spec spec, Wishlist wishlist) {
        String owner = wishlist.repoOwner(spec);
        for (String activeId : pool.ids()) {
            boolean sharesRepo = wishlist.byId(activeId)
                    .map(other -> wishlist.repoOwner(other).equals(owner))
                    .orElse(false);
            if (sharesRepo) {
                return "queued behind " + activeId + ", which is in the same repository";
            }
        }
        return null;
    }

    /**
     * Whether the vendor this project would use is out of quota.
     *
     * <p>A project that has not started yet and says {@code assign: auto} is only blocked when
     * <em>both</em> vendors are out — otherwise it simply gets assigned to the one still working.
     */
    private String vendorBlockedReason(Spec spec, Wishlist wishlist) {
        if (quota == null) {
            return null;
        }
        // Survey: this only words a reason, and it sits under both the list and the queue.
        RunState state = RunStore.survey(pathsFor(spec, wishlist).stateFile()).orElse(null);
        AgentKind assigned = state != null && state.getAssignedTo() != null
                ? whoWouldBeCalled(state)
                : spec.assignedAgent().orElse(null);

        if (assigned != null) {
            return quota.blockedUntil(assigned)
                    .map(until -> assigned + closed(assigned) + local(until))
                    .orElse(null);
        }
        boolean bothOut = quota.isBlocked(AgentKind.CLAUDE) && quota.isBlocked(AgentKind.CODEX);
        return bothOut ? "both vendors are out of quota" : null;
    }

    /**
     * The vendor this project's next call would actually reach.
     *
     * <p>Usually the one that builds it — but a project waiting to be reviewed calls the
     * <em>other</em> model, and that is the whole point of a cross-review. Checking the builder's
     * quota there would start a project only for it to spend a call discovering a limit already
     * recorded, then suspend on it.
     */
    private AgentKind whoWouldBeCalled(RunState state) {
        // resumePhase as well as phase. A project suspended while waiting to be reviewed is the
        // normal way that state is reached, and checking only phase meant such a project was
        // measured against its builder's quota: it looked runnable, started, spent a call
        // discovering the reviewer was out, and suspended again. Once per project, every pass.
        if (!awaitingReview(state)) {
            return state.getAssignedTo();
        }
        // The first reviewer that could actually be called, not merely the first in the list:
        // measuring against the wrong vendor's window makes a runnable project look blocked, or a
        // blocked one look runnable.
        List<AgentKind> candidates = agents.reviewersFor(state.getAssignedTo());
        return candidates.stream()
                .filter(kind -> quota == null || !quota.isBlocked(kind))
                .findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElse(state.getAssignedTo()));
    }

    /**
     * Why this project cannot be cross-reviewed right now, or null when it can.
     *
     * <p>Asked of the reviewing vendor specifically, which is the one that did not build it. A
     * button offering an action that is certain to be refused is worse than a button that says why
     * it is unavailable.
     */
    public String reviewerWait(RunState state) {
        if (quota == null || state.getAssignedTo() == null) {
            return null;
        }
        List<AgentKind> candidates = agents.reviewersFor(state.getAssignedTo());
        if (candidates.isEmpty()) {
            return "no model other than " + state.getAssignedTo() + " is installed to review this";
        }
        // Blocked only when every one of them is. With a single possible reviewer this reads the
        // same as it always did; with more, it stops claiming a project is stuck while a vendor
        // that could read it is sitting idle.
        if (candidates.stream().anyMatch(kind -> !quota.isBlocked(kind))) {
            return null;
        }
        return Reviewers.soonest(state.getAssignedTo(), agents.kinds(), null, quota)
                .map(wait -> wait.kind() + " reviews this, and is out of quota until " + local(wait.until()))
                .orElse(null);
    }

    private boolean finished(Spec spec) {
        return RunStore.survey(RunPaths.under(home.runs(), spec.id()).stateFile())
                .map(state -> state.getPhase().terminal())
                .orElse(false);
    }

    /**
     * How a vendor on hold is closed: out of quota, or unusable on this machine.
     *
     * <p>The ledger also holds a vendor that is logged out or whose sandbox is broken, and telling a
     * person to wait out a quota window that does not exist sends them to wait rather than to fix.
     */
    private String closed(AgentKind kind) {
        QuotaWindow window = quota.current().get(kind.name());
        String message = window == null || window.getMessage() == null ? "" : window.getMessage();
        if (message.startsWith(AgentOutcome.NOT_AUTHENTICATED.name())) {
            return " is not logged in; held until ";
        }
        if (message.startsWith(AgentOutcome.TOOL_BROKEN.name())) {
            return "'s sandbox will not start a process; held until ";
        }
        return " is out of quota until ";
    }

    /**
     * A reset time as a person reads it: the time alone when it is today, with its day otherwise.
     *
     * <p>Codex's weekly limit made the difference matter. "CODEX is out of quota until 5:39 PM" was
     * shown on the Sunday for a reset on the Friday, and read as later that afternoon.
     */
    static String local(Instant when) {
        ZoneId zone = ZoneId.systemDefault();
        boolean today = when.atZone(zone).toLocalDate().equals(LocalDate.now(zone));
        return DateTimeFormatter.ofPattern(today ? "h:mm a" : "EEE MMM d, h:mm a", Locale.ENGLISH)
                .withZone(zone).format(when);
    }
}
