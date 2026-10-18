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
            return fallback(kind, baseline, ladder);
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
        List<String> ladder = tiers.forVendor(kind);
        if (named.isBlank()) {
            return top(kind);
        }
        return ladder.contains(named) || ladder.isEmpty() ? named : fallback(kind, named, ladder);
    }

    /**
     * What to run when the spec names a model the vendor no longer offers.
     *
     * <p>The bottom rung, and said out loud. The ladder is refreshed from the vendors at startup,
     * so a model missing from it is one this account cannot reach — and using it as written spends
     * the whole turn being refused, three times over, before the project is parked for a person.
     * The cheapest rung is the one choice certain to run, which beats not running at all.
     *
     * <p>It is announced because it is Forge doing something other than what the spec asked for,
     * and the spec's author is the only one who can decide whether the substitute is good enough.
     */
    private String fallback(AgentKind kind, String named, List<String> ladder) {
        String lowest = ladder.getFirst();
        System.err.println(kind + " no longer offers " + named + "; using " + lowest
                + " instead - set a model it does offer in the spec, or run 'forge models'");
        return lowest;
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
