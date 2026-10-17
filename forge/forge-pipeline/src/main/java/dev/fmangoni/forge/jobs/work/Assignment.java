package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chooses which model builds a project whose spec says {@code assign: auto}.
 *
 * <p>Balances by outstanding work rather than alternating blindly, and counts projects already
 * assigned in previous runs. Alternating from scratch each invocation would pile new work onto a
 * vendor that is already carrying a backlog of half-finished projects from last night.
 */
public final class Assignment {

    private final Map<AgentKind, Integer> load = new EnumMap<>(AgentKind.class);

    /** Consulted so new work is not handed to a vendor that is currently out of quota. */
    private QuotaLedger quota;

    /** The user's own account of which vendor can afford to build; never null after construction. */
    private VendorPolicy policy = new VendorPolicy(new LinkedHashMap<>());

    public Assignment(Collection<RunState> existing, QuotaLedger quota) {
        this(existing);
        this.quota = quota;
    }

    public Assignment(Collection<RunState> existing, QuotaLedger quota,
                      VendorPolicy policy) {
        this(existing, quota);
        if (policy != null) {
            this.policy = policy;
        }
    }

    /** @param existing states from earlier runs, so their assignments count towards the balance */
    public Assignment(Collection<RunState> existing) {
        for (AgentKind kind : AgentKind.values()) {
            load.put(kind, 0);
        }
        existing.stream()
                .filter(state -> state.getAssignedTo() != null && !state.getPhase().terminal())
                .forEach(state -> record(state.getAssignedTo()));
    }

    /** An explicit {@code assign:} in the spec always wins; {@code auto} goes to the lighter vendor. */
    public AgentKind decide(Spec spec) {
        AgentKind chosen = spec.assignedAgent().orElseGet(this::lighter);
        record(chosen);
        return chosen;
    }

    private AgentKind lighter() {
        // A vendor the user has said should not take projects is skipped outright, unless that
        // would leave nobody to build at all -- a setting that stopped the queue entirely would be
        // a worse answer than ignoring it.
        if (!policy.nobodyBuilds()) {
            boolean claudeBuilds = policy.buildsNewProjects(AgentKind.CLAUDE);
            if (claudeBuilds != policy.buildsNewProjects(AgentKind.CODEX)) {
                return claudeBuilds ? AgentKind.CLAUDE : AgentKind.CODEX;
            }
        }
        // An available vendor beats a balanced one: assigning to a vendor that is out of quota
        // buys a project that cannot start, however even the workload looks.
        boolean claudeOut = quota != null && quota.isBlocked(AgentKind.CLAUDE);
        boolean codexOut = quota != null && quota.isBlocked(AgentKind.CODEX);
        if (claudeOut != codexOut) {
            return claudeOut ? AgentKind.CODEX : AgentKind.CLAUDE;
        }
        // Ties go to CLAUDE only because something has to break them deterministically; over a
        // queue of several projects the counts even out regardless of which side starts.
        return load.get(AgentKind.CLAUDE) <= load.get(AgentKind.CODEX)
                ? AgentKind.CLAUDE
                : AgentKind.CODEX;
    }

    private void record(AgentKind kind) {
        load.merge(kind, 1, Integer::sum);
    }
}
