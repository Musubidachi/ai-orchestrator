package dev.fmangoni.forge.jobs.queue;

import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.LockedElsewhere;
import dev.fmangoni.forge.project.DashboardSettings;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.rank.Ranking;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import dev.fmangoni.forge.state.UnreadableState;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The unattended queue: whether it runs, in what order, and which projects it may start.
 *
 * <p>Deciding only. Launching is {@link PipelineJobs}, and the loop that joins the two is
 * {@link JobRunner#fillCapacity}, under the runner's lock.
 */
public final class JobQueue {

    private final ForgeHome home;
    private final StartRules rules;
    private final JobPool pool;
    private final LockedElsewhere elsewhere;

    /**
     * Whether to keep the queue moving without being asked.
     *
     * <p>Off until switched on, and then remembered in {@code dashboard.json} — see
     * {@link DashboardSettings}. Starting coding agents because a browser tab opened is not a
     * reasonable default for a fresh clone; continuing to do so on a machine where it was turned
     * on deliberately is.
     */
    private volatile boolean autoRun;

    public JobQueue(ForgeHome home, StartRules rules, JobPool pool, LockedElsewhere elsewhere) {
        this.home = home;
        this.rules = rules;
        this.pool = pool;
        this.elsewhere = elsewhere;
    }

    public boolean isAutoRun() {
        return autoRun;
    }

    /**
     * Turns automatic queue-filling on or off, and remembers it.
     *
     * <p>Persisted so the setting survives the next restart. Without this the editor forgot it every
     * time it came up, and the queue quietly stopped.
     */
    public void setAutoRun(boolean enabled) {
        this.autoRun = enabled;
        new DashboardSettings(enabled).save(home.root());
    }

    /**
     * Switches auto-run back on at startup if it was on before, and says whether it did.
     *
     * <p>A fresh clone starts nothing, because launching an editor is not consent to spend a
     * subscription.
     */
    public boolean resumeSetting() {
        if (!DashboardSettings.load(home.root()).isAutoRun()) {
            return false;
        }
        this.autoRun = true;
        return true;
    }

    /**
     * Rank order, except that a project waiting to be reviewed goes to the front.
     *
     * <p>Rank says which project matters most. It does not say what a turn costs, and those are
     * wildly different: a cross-review is one read-only turn that finishes a project, while a build
     * turn is one of a dozen or more that leave it exactly as unfinished as before.
     *
     * <p>Ignoring that has a specific, observed failure. The reviewer is by definition the vendor
     * that did <em>not</em> build the project, so on a lopsided pair of subscriptions every review
     * lands on the same side. Here that side spent its whole window on one build turn while three
     * finished projects sat waiting for one cheap turn each — and the vendor with quota to spare
     * had nothing left to do, because it had built all three itself.
     *
     * <p>So reviews are not merely cheaper; they are the work that releases other work. Doing them
     * first is what keeps a scarce vendor from becoming a queue for everything.
     */
    public List<Spec> reviewsFirst(Wishlist wishlist) {
        return Ranking.runOrder(wishlist).stream()
                .sorted(Comparator.comparingInt(spec -> rules.awaitingReview(spec, wishlist) ? 0 : 1))
                .toList();
    }

    /**
     * Runnable now, without overriding anything the user or a vendor asked for.
     *
     * <p>Deliberately does not clear a quota wait. That is the one thing an explicit Start does
     * that the queue must not: retrying a rate-limited vendor early just fails again, and doing it
     * on a timer would turn one exhausted window into a loop of pointless calls.
     */
    public boolean eligible(Spec spec, Wishlist wishlist) {
        if (pool.isRunning(spec.id()) || rules.blockedReason(spec, wishlist) != null
                || elsewhere.waiting(spec.id())) {
            return false;
        }
        RunState state;
        try {
            state = RunStore.load(rules.pathsFor(spec, wishlist).stateFile()).orElse(null);
        } catch (UnreadableState e) {
            // Not eligible, and not "never run" either: that would create a fresh project over it.
            return false;
        }
        return state == null || rules.eligibility().ready(state);
    }

    /**
     * Where each spec sits in the run queue, in priority order.
     *
     * <p>Computed over the whole wishlist rather than per row, because a position only means
     * anything relative to everything else competing for the same slots.
     */
    public Map<String, Integer> positions(Wishlist wishlist) {
        Map<String, Integer> positions = new HashMap<>();
        int place = 0;
        for (Spec spec : Ranking.runOrder(wishlist)) {
            // eligible already means "could start now": not running, not blocked, not finished,
            // and not waiting out a quota window it was told to respect.
            if (eligible(spec, wishlist)) {
                positions.put(spec.id(), ++place);
            }
        }
        return positions;
    }
}
