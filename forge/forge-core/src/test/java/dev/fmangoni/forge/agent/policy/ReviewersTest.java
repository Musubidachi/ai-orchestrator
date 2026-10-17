package dev.fmangoni.forge.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.model.ReviewerWait;
import dev.fmangoni.forge.agent.policy.model.VendorSettings;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Choosing who reads a project its author cannot judge.
 *
 * <p>Written because the previous answer was {@code AgentKind.other()} — the only remaining
 * constant of two — which had no way to express "that one is out of quota, ask someone else". A
 * project here sat unreviewed for a day and a half on exactly that.
 */
class ReviewersTest {

    @TempDir
    Path root;

    private QuotaLedger quota;

    @BeforeEach
    void setUp() {
        quota = new QuotaLedger(root.resolve("quota.json"));
    }

    private static final Set<AgentKind> ALL = EnumSet.allOf(AgentKind.class);

    /** A policy where the named vendors do not take projects of their own. */
    private VendorPolicy reviewOnly(AgentKind... kinds) {
        Map<String, VendorSettings> map = new LinkedHashMap<>();
        for (AgentKind kind : AgentKind.values()) {
            VendorSettings vendor = new VendorSettings();
            vendor.setBuilds(true);
            map.put(kind.name(), vendor);
        }
        for (AgentKind kind : kinds) {
            map.get(kind.name()).setBuilds(false);
        }
        return new VendorPolicy(map);
    }

    private void blocked(AgentKind kind) {
        quota.record(kind, Instant.now().plusSeconds(3600), "you have hit your usage limit");
    }

    @Test
    @DisplayName("the builder never reviews its own work")
    void neverTheAuthor() {
        for (AgentKind builder : AgentKind.values()) {
            assertFalse(Reviewers.candidates(builder, ALL, reviewOnly()).contains(builder),
                    builder + " was offered its own diff to review");
        }
    }

    @Test
    @DisplayName("a vendor whose CLI is not installed is never chosen")
    void onlyWhatIsInstalled() {
        Set<AgentKind> onlyClaude = EnumSet.of(AgentKind.CLAUDE);

        assertEquals(Reviewers.candidates(AgentKind.CLAUDE, onlyClaude, reviewOnly()).size(), 0,
                "with only the builder installed there is nobody to review");
        assertTrue(Reviewers.available(AgentKind.CLAUDE, onlyClaude, reviewOnly(), quota).isEmpty());
    }

    @Test
    @DisplayName("a reviewer that is out of quota is passed over for one that is not")
    void skipsBlockedVendors() {
        // The whole point. Before this, a rate-limited reviewer meant no review at all, even with
        // another vendor sitting idle.
        AgentKind builder = AgentKind.CLAUDE;
        List<AgentKind> others = Reviewers.candidates(builder, ALL, reviewOnly());
        Assumptions.assumeTrue(others.size() > 1,
                "needs at least two possible reviewers to be a meaningful test");

        blocked(others.get(0));

        assertEquals(others.get(1),
                Reviewers.available(builder, ALL, reviewOnly(), quota).orElseThrow());
    }

    @Test
    @DisplayName("nobody is available when every reviewer is blocked")
    void noneAvailableWhenAllBlocked() {
        for (AgentKind kind : AgentKind.values()) {
            blocked(kind);
        }

        assertTrue(Reviewers.available(AgentKind.CLAUDE, ALL, reviewOnly(), quota).isEmpty());
    }

    @Test
    @DisplayName("a vendor that never builds is asked before one that does")
    void reviewOnlyVendorsGoFirst() {
        // Reviewing is one read-only turn; building is a dozen carrying a growing conversation.
        // Spending a building vendor's window on a review a spare vendor could do is the trade
        // `builds: false` exists to avoid.
        List<AgentKind> candidates =
                Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly(AgentKind.CODEX));

        Assumptions.assumeTrue(candidates.size() > 1,
                "needs a spare vendor besides the builder to be a meaningful test");
        assertEquals(AgentKind.CODEX, candidates.get(0));
    }

    @Test
    @DisplayName("a blocked vendor is asked last, not skipped")
    void blockedVendorsGoLastButStayInTheList() {
        // Ordering rather than exclusion, deliberately. The ledger is a memory of what a vendor
        // said earlier; a completed call is the only proof a window has reopened. A stale record
        // here left a finished project unreviewed for a day, and what fixed it was Codex being
        // tried anyway and simply working — which cannot happen if blocked vendors are dropped.
        List<AgentKind> before =
                Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly(), quota);
        Assumptions.assumeTrue(before.size() > 1,
                "needs at least two possible reviewers to be a meaningful test");

        blocked(before.get(0));
        List<AgentKind> after =
                Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly(), quota);

        assertEquals(before.size(), after.size(), "a blocked vendor must not vanish");
        assertEquals(before.get(0), after.get(after.size() - 1), "it should be last, not gone");
        assertTrue(after.contains(before.get(0)));
    }

    @Test
    @DisplayName("being able to answer outranks being the cheaper vendor to spend")
    void availabilityOutranksThrift() {
        // Both preferences at once, pulling opposite ways: the review-only vendor is blocked and
        // the builder is free. Asking the blocked one first costs a whole turn to be told no.
        List<AgentKind> candidates =
                Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly(AgentKind.CODEX), quota);
        Assumptions.assumeTrue(candidates.size() > 1,
                "needs a spare vendor besides the builder to be a meaningful test");

        blocked(AgentKind.CODEX);

        assertNotEquals(AgentKind.CODEX,
                Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly(AgentKind.CODEX), quota)
                        .get(0),
                "a vendor that cannot answer should not be first in line to be asked");
    }

    @Test
    @DisplayName("the wait reported is the soonest reviewer to come back")
    void reportsTheSoonestWindow() {
        List<AgentKind> others = Reviewers.candidates(AgentKind.CLAUDE, ALL, reviewOnly());
        Assumptions.assumeTrue(others.size() > 1,
                "needs at least two possible reviewers to be a meaningful test");

        Instant later = Instant.now().plusSeconds(7200);
        Instant sooner = Instant.now().plusSeconds(600);
        quota.record(others.get(0), later, "out");
        quota.record(others.get(1), sooner, "out");

        ReviewerWait wait =
                Reviewers.soonest(AgentKind.CLAUDE, ALL, reviewOnly(), quota).orElseThrow();

        assertEquals(others.get(1), wait.kind());
        assertEquals(sooner, wait.until());
    }

    @Test
    @DisplayName("no wait is reported when a reviewer is free")
    void noWaitWhenSomeoneIsFree() {
        assertTrue(Reviewers.soonest(AgentKind.CLAUDE, ALL, reviewOnly(), quota).isEmpty());
    }

    @Test
    @DisplayName("every vendor has somebody who could review its work")
    void everyVendorHasAReviewer() {
        // A sweep rather than a list, so a vendor added later is covered without anyone
        // remembering to extend this. A vendor with no possible reviewer can never be accepted.
        for (AgentKind builder : AgentKind.values()) {
            assertFalse(Reviewers.candidates(builder, ALL, reviewOnly()).isEmpty(),
                    "nothing could ever review a project built by " + builder);
        }
    }
}
