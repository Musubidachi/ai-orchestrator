package dev.fmangoni.forge.agent.policy;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.model.ReviewerWait;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Who reads a project its author cannot judge.
 *
 * <p>This replaces {@code AgentKind.other()}, which answered the question by returning the only
 * remaining constant. That was correct while there were exactly two vendors and quietly wrong the
 * moment there were more — and it was already unhelpful with two, because "the other one" is not an
 * answer when the other one is out of quota. A project here sat at DONE for a day and a half saying
 * <em>reviewer unavailable: RATE_LIMITED</em>, with a second opinion available in principle and no
 * way to ask for one.
 *
 * <p>The one rule that is not negotiable: never the builder. A model reviewing its own diff will
 * find what it was already looking for, and a clean review from the author is worth less than no
 * review at all because it reads the same as a real one.
 */
@UtilityClass
public class Reviewers {

    /**
     * Reviewers worth trying, best first.
     *
     * <p>Ordered so that a vendor which does not take projects of its own is asked before one that
     * does. Reviewing is a single read-only turn; building is a dozen turns carrying a growing
     * conversation. Spending a scarce building vendor's window on a review it could have spent
     * building — when a vendor that will never build anything is sitting there idle — is the exact
     * trade {@code builds: false} exists to avoid.
     *
     * @param builder   who wrote the code, and therefore cannot review it
     * @param installed the vendors whose CLI is actually present on this machine
     */
    public List<AgentKind> candidates(AgentKind builder, Set<AgentKind> installed,
                                      VendorPolicy policy) {
        return candidates(builder, installed, policy, null);
    }

    /**
     * @param quota consulted only to order, never to exclude. A vendor the ledger calls blocked is
     *              tried last rather than skipped, because the ledger is a memory of what a vendor
     *              said earlier and a completed call is the only proof a window has reopened.
     *              Skipping outright is how a stale record becomes permanent: one here outlived
     *              its window and left a finished project unreviewed for a day, and the thing that
     *              fixed it was Codex being tried anyway and simply working.
     */
    public List<AgentKind> candidates(AgentKind builder, Set<AgentKind> installed,
                                      VendorPolicy policy, QuotaLedger quota) {
        List<AgentKind> out = new ArrayList<>();
        for (AgentKind kind : AgentKind.values()) {
            if (kind != builder && installed.contains(kind)) {
                out.add(kind);
            }
        }
        // Two keys, most important first. A vendor that is out of quota goes last whatever else is
        // true of it: calling it costs a full turn to be told no, and on the review path that was
        // three minutes before the vendor that could have answered was even asked. Among the rest,
        // one that never builds is asked before one that does — reviewing is a single read-only
        // turn, and spending a building vendor's window on it when a spare is idle is the trade
        // `builds: false` exists to avoid.
        //
        // Stable, so equal-preference vendors keep declaration order and which model reviews a
        // given project does not change between passes for no reason.
        out.sort(Comparator
                .comparing((AgentKind kind) -> quota != null && quota.isBlocked(kind))
                .thenComparing(kind -> policy != null && policy.buildsNewProjects(kind)));
        return out;
    }

    /** The first candidate that can be called right now, or empty if every one is blocked. */
    public Optional<AgentKind> available(AgentKind builder, Set<AgentKind> installed,
                                         VendorPolicy policy, QuotaLedger quota) {
        return candidates(builder, installed, policy).stream()
                .filter(kind -> quota == null || !quota.isBlocked(kind))
                .findFirst();
    }

    /**
     * When the first reviewer comes back, for a project where none can be called yet.
     *
     * <p>The soonest of them rather than any particular one: what a person wants from a card that
     * says a project is stuck is when it stops being stuck.
     */
    public Optional<ReviewerWait> soonest(AgentKind builder, Set<AgentKind> installed,
                                  VendorPolicy policy, QuotaLedger quota) {
        if (quota == null) {
            return Optional.empty();
        }
        ReviewerWait best = null;
        for (AgentKind kind : candidates(builder, installed, policy)) {
            Instant until = quota.blockedUntil(kind).orElse(null);
            if (until != null && (best == null || until.isBefore(best.until()))) {
                best = new ReviewerWait(kind, until);
            }
        }
        return Optional.ofNullable(best);
    }

}
