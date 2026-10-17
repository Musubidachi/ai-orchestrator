package dev.fmangoni.forge.walkthrough;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import dev.fmangoni.forge.plan.PlanStep;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.util.ArrayList;
import java.util.List;

/**
 * What a project is, said out loud.
 *
 * <p>Everything here is read off the run's own files — the spec's title, the agent's handoff, the
 * gate's verdict, the security report and the cross-review. Nothing is generated, so listening to
 * this costs no quota and cannot drift from what is on disk: it is the report, in the order you
 * would want it spoken rather than the order you would want it laid out.
 *
 * <p>The order is deliberate. What it is, then whether it worked, then what it does, then what it
 * would take to trust it. A listener cannot skim back up the page, so the thing that decides
 * whether the rest is worth hearing goes first.
 */
public final class Walkthrough {

    /** Enough of the agent's own summary to be useful, before it turns into an essay. */
    private static final int SUMMARY_LIMIT = 400;

    private final RunState state;
    private final Review review;
    private final SecurityReport security;
    private final List<String> said = new ArrayList<>();

    private Walkthrough(RunState state, Review review, SecurityReport security) {
        this.state = state;
        this.review = review;
        this.security = security;
    }

    /** The whole walkthrough as plain prose: no markup, because a synthesiser reads markup aloud. */
    public static String of(RunState state, Review review, SecurityReport security) {
        Walkthrough walkthrough = new Walkthrough(state, review, security);
        walkthrough.whatItIs();
        walkthrough.howItWent();
        walkthrough.whatItDoes();
        walkthrough.theFlow();
        walkthrough.whatIsNotProven();
        walkthrough.whatTheReviewerSaid();
        walkthrough.whatSecurityFound();
        walkthrough.whatIsWaitingOnYou();
        return String.join(" ", walkthrough.said);
    }

    /** Reads the walkthrough for a run straight off disk. */
    public static String of(RunPaths paths, RunState state, Review review) {
        return of(state, review, SecurityReport.load(paths.securityFile()));
    }

    private void say(String sentence) {
        said.add(Spoken.sentence(sentence));
    }

    private void whatItIs() {
        String built = state.getAssignedTo() == null ? "" : ", built by " + state.getAssignedTo();
        say(Spoken.plain(state.getTitle()) + built);
    }

    private void howItWent() {
        Phase phase = state.getPhase();
        CommandResult failure = state.getLastFailure();
        if (phase == Phase.ACCEPTED) {
            say("You have accepted it");
        } else if (state.getNeedsPerson() != null && !phase.terminal()) {
            // Before the phase: a held project can be in any of them, and "will carry on by itself"
            // is precisely what it will not do.
            say("It has stopped, and needs you: " + Spoken.plain(state.getNeedsPerson())
                    + ". It will not carry on until you press Start");
        } else if (phase == Phase.DONE) {
            say("Every check passed, and it is waiting for you to look at it");
        } else if (phase == Phase.PARKED) {
            say("It is parked, and needs you. " + (state.getLastError() == null
                    ? "No reason was recorded" : Spoken.plain(state.getLastError())));
        } else if (phase == Phase.SUSPENDED) {
            say("It is waiting on a vendor's quota window, and will carry on by itself");
        } else {
            say("It is still being built. Right now it is at the " + Spoken.words(phase.name().toLowerCase())
                    + " step");
        }
        if (failure != null) {
            say("The last failing command was " + Spoken.command(failure.command()));
        }
        String effort = Spoken.count(state.getSessions(), "session");
        String rounds = state.getRepairRounds() == 0
                ? "no repair rounds"
                : Spoken.count(state.getRepairRounds(), "repair round");
        say("It has taken " + effort + ", " + rounds + ", and "
                + Spoken.duration(state.getWorkedMillis() / 1000) + " of work");
    }

    private void whatItDoes() {
        Plan plan = usablePlan();
        if (plan == null) {
            say("The agent left no account of what it built");
            return;
        }
        if (!plan.getSummary().isBlank()) {
            say("In its own words: " + shortened(plan.getSummary()));
        }
        List<PlanFeature> features = plan.getFeatures();
        if (features != null && !features.isEmpty()) {
            say("It claims " + Spoken.count(features.size(), "feature") + ": "
                    + Spoken.and(features.stream().map(PlanFeature::getName).toList()));
        }
    }

    private void theFlow() {
        Plan plan = usablePlan();
        String overview = plan == null ? "" : plan.getOverview();
        if (overview == null || overview.isBlank()) {
            say("It named no class as its top-level flow, which the gate treats as a failure");
            return;
        }
        say("The class that shows the shape of it is " + Spoken.typeName(overview));
    }

    private void whatIsNotProven() {
        Plan plan = usablePlan();
        List<String> gaps = plan == null ? List.of() : plan.getNotVerified();
        if (gaps == null || gaps.isEmpty()) {
            return;
        }
        say("The agent says the passing checks do not prove " + Spoken.count(gaps.size(), "thing")
                + ". The first is: " + shortened(gaps.getFirst()));
        if (gaps.size() > 1) {
            say("The rest are on the report");
        }
    }

    private void whatTheReviewerSaid() {
        if (review == null || review.isUnavailable()) {
            say("No other model has read this yet, so it cannot be accepted");
            return;
        }
        if (review.getParseError() != null) {
            say("The reviewer answered with something nobody could read, so this counts as unreviewed");
            return;
        }
        List<Finding> blocking = review.blocking();
        if (blocking.isEmpty()) {
            say("Another model read it and raised nothing blocking");
        } else {
            say("Another model raised " + Spoken.count(blocking.size(), "blocking finding")
                    + ". The first is: " + shortened(blocking.getFirst().getSummary()));
        }
        long unproven = review.unprovenFeatures().size();
        if (unproven > 0) {
            say("A first pass could not find " + Spoken.count(unproven, "claimed feature")
                    + " in the code");
        }
    }

    private void whatSecurityFound() {
        if (security == null) {
            say("The security check has not been run on it");
            return;
        }
        int ruling = security.forRuling().size();
        if (security.findings().isEmpty()) {
            say("The security check found nothing");
        } else if (ruling == 0) {
            say("The security check found " + Spoken.count(security.findings().size(), "thing")
                    + " worth noting, none of which needs a decision");
        } else {
            say("The security check found " + Spoken.count(ruling, "thing") + " that needs your decision");
        }
    }

    private void whatIsWaitingOnYou() {
        Plan plan = usablePlan();
        List<PlanStep> remaining = plan == null ? List.of() : plan.getRemaining();
        if (remaining != null && !remaining.isEmpty()) {
            say("The agent says " + Spoken.count(remaining.size(), "piece") + " of work is left. Next up: "
                    + shortened(remaining.getFirst().getStep()));
        }
        if (state.getPhase() == Phase.DONE) {
            say("To accept it, go through the list on its report");
        }
    }

    private Plan usablePlan() {
        return state.getPlan() != null && state.getPlan().usable() ? state.getPlan() : null;
    }

    /** Model-written prose can run for paragraphs; a listener needs the first breath of it. */
    private String shortened(String text) {
        String flat = Spoken.plain(text);
        if (flat.length() <= SUMMARY_LIMIT) {
            return flat;
        }
        int cut = flat.lastIndexOf(' ', SUMMARY_LIMIT);
        return flat.substring(0, cut < 0 ? SUMMARY_LIMIT : cut) + ", and it goes on";
    }
}
