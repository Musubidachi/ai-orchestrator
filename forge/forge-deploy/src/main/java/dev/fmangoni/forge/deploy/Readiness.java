package dev.fmangoni.forge.deploy;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Whether a project is fit to leave this machine: deployed, or pushed to GitHub.
 *
 * <p>One set of conditions for both, kept in one place so that a project refused a deploy for an
 * unruled security finding cannot be published somewhere else instead.
 */
public final class Readiness {

    private Readiness() {
    }

    /**
     * Why the project cannot go out, or null when it can.
     *
     * @param done   how the sentence ends for the act, such as "deployed"
     * @param doing  the same in the middle of one, such as "deploying"
     */
    public static String blockedReason(RunState state, RunPaths paths, String done, String doing) {
        if (state == null || state.getPhase() != Phase.ACCEPTED) {
            return "only an accepted project can be " + done;
        }
        if (state.getLastFailure() != null) {
            return "its last gate did not pass";
        }
        SecurityReport security = SecurityReport.load(paths.securityFile());
        if (security == null) {
            return "run the security check before " + doing + " it";
        }
        return unresolvedSecurity(security, paths);
    }

    /**
     * Security findings that still stand in the way: unruled, or sent back.
     *
     * <p>A ruling saved on an accepted project's report counts. Submissions are written before any
     * refusal, so a person can rule on a finding a later check turned up without reopening anything.
     */
    private static String unresolvedSecurity(SecurityReport security, RunPaths paths) {
        Map<String, Decision> ruled = DecisionStore.load(paths.decisionsFile()).stream()
                .collect(Collectors.toMap(Decision::getItemId, d -> d, (a, b) -> b));
        List<ReviewItem> items = ReviewItem.of(null, null, security);
        long unruled = items.stream().filter(item -> !ruled.containsKey(item.id())).count();
        long sentBack = items.stream().filter(item -> ruled.containsKey(item.id())
                && ruled.get(item.id()).wantsChanges()).count();
        if (unruled > 0) {
            return unruled + " security finding(s) need a ruling on the report first";
        }
        if (sentBack > 0) {
            return sentBack + " security finding(s) were sent back and have not been fixed";
        }
        return null;
    }
}
