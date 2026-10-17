package dev.fmangoni.forge.spec.tier;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.List;

/**
 * Which model does which job on a project, read off the vendor's ladder in {@code models.json}.
 *
 * <p>The jobs are not equally hard, and paying the top tier for all of them is what made a project
 * expensive. Deciding what to build is one call and the one that sets the direction for every turn
 * after it, so it gets the best model there is. Following a plan that already exists is ordinary
 * work: it runs a rung or two down. Checking whether each claimed feature is really there is
 * narrow, mechanical reading, so it runs at the bottom. The one judgement that has to be right —
 * is this change actually correct — runs at the top again, once.
 *
 * <p>A spec that names a model for a vendor has made a decision, and it is kept: that model is the
 * baseline for the turns that write code. It does not pin the planning or reviewing jobs, which are
 * about how good the reading needs to be rather than about this project's taste in models.
 *
 * <p>Everything resolves against the ladder as configured, so a vendor with two tiers and one with
 * six both get sensible answers, and adding a model to {@code models.json} moves the rungs without
 * a change here. An empty ladder yields {@code ""}, which leaves the CLI's own default alone.
 */
public final class ModelChoice {

    private final ModelTiers tiers;
    private final Spec spec;

    public ModelChoice(ModelTiers tiers, Spec spec) {
        this.tiers = tiers;
        this.spec = spec;
    }

    /** Drafting the plan: the best model the vendor offers. */
    public String plan(AgentKind kind) {
        return top(kind);
    }

    /**
     * Writing the code, following the plan.
     *
     * <p>Starts at the spec's model, or the middle of the ladder when the spec names none, and
     * climbs one rung for each repair round already spent. A project that cannot get its gate to
     * pass is not helped by another attempt from the same model that just failed; by the time the
     * rounds run out it is asking the strongest model the vendor has. The middle rather than the
     * bottom is deliberate: a model too weak to do the work fails the gate repeatedly, and three
     * cheap turns plus three gate runs cost more than one turn that works.
     *
     * @param repairRounds repair rounds already spent on this project
     */
    public String implement(AgentKind kind, int repairRounds) {
        List<String> ladder = tiers.forVendor(kind);
        if (ladder.isEmpty()) {
            return spec.modelFor(kind);
        }
        String baseline = spec.modelFor(kind);
        int start = baseline.isBlank() ? middleIndex(ladder) : ladder.indexOf(baseline);
        if (start < 0) {
            // A model the ladder does not list: the spec means it, and there is no rung to climb
            // from, so it is used as given rather than replaced by a guess.
            return baseline;
        }
        return ladder.get(Math.clamp((long) start + Math.max(0, repairRounds), 0, ladder.size() - 1));
    }

    /**
     * The one big review: the best model, because this is the judgement the whole pipeline turns on.
     *
     * <p>A spec that names a model for the reviewing vendor still wins; that vendor's slider in the
     * editor is a deliberate setting and not a default to be overridden.
     */
    public String bigReview(AgentKind kind) {
        String named = spec.modelFor(kind);
        return named.isBlank() ? top(kind) : named;
    }

    /**
     * The per-feature pass before it: the cheapest model the vendor offers.
     *
     * <p>Always the bottom rung, whatever the spec says, because the question is narrow — does the
     * code contain this feature, and does the evidence show it — and asking it of an expensive
     * model once per feature is exactly the consumption this split exists to avoid.
     */
    public String featureReview(AgentKind kind) {
        List<String> ladder = tiers.forVendor(kind);
        return ladder.isEmpty() ? "" : ladder.getFirst();
    }

    private String top(AgentKind kind) {
        List<String> ladder = tiers.forVendor(kind);
        return ladder.isEmpty() ? "" : ladder.getLast();
    }

    private static int middleIndex(List<String> ladder) {
        return (ladder.size() - 1) / 2;
    }
}
