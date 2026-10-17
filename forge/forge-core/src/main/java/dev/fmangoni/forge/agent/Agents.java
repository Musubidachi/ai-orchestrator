package dev.fmangoni.forge.agent;

import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.policy.Reviewers;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.agent.vendor.AntigravityAgent;
import dev.fmangoni.forge.agent.vendor.ClaudeAgent;
import dev.fmangoni.forge.agent.vendor.CliLocator;
import dev.fmangoni.forge.agent.vendor.CodexAgent;
import dev.fmangoni.forge.agent.vendor.model.CodexOptions;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves an {@link AgentKind} to a configured {@link Agent}, so the pipeline never constructs one. */
public final class Agents {

    private final Map<AgentKind, Agent> agents;

    /**
     * Consulted only to order reviewers, and allowed to be absent.
     *
     * <p>Tests build a registry directly from a map and have no policy file; the ordering they get
     * is declaration order, which is a preference rather than a correctness property.
     */
    private final VendorPolicy policy;

    /** Ordering only: a blocked vendor is asked last, never skipped. May be absent in tests. */
    private final QuotaLedger quota;

    public Agents(Map<AgentKind, Agent> agents) {
        this(agents, null, null);
    }

    public Agents(Map<AgentKind, Agent> agents, VendorPolicy policy) {
        this(agents, policy, null);
    }

    public Agents(Map<AgentKind, Agent> agents, VendorPolicy policy,
                  QuotaLedger quota) {
        this.agents = agents;
        this.policy = policy;
        this.quota = quota;
    }

    /**
     * The vendors this Forge can actually call.
     *
     * <p>Registration is the honest test of availability: a CLI that is not installed never makes
     * it into the map, so nothing downstream has to ask the filesystem a second time.
     */
    public Set<AgentKind> kinds() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    /**
     * When this vendor's window reopens, if it is known to be closed right now.
     *
     * <p>For a caller that would rather not ask at all than be refused. Empty without a ledger, or
     * when nothing is known: a call is the only real test, and this never claims a vendor is open.
     */
    public Optional<Instant> closedUntil(AgentKind kind) {
        return quota == null ? Optional.empty() : quota.blockedUntil(kind);
    }

    /** Who could read a project this vendor built, best first. Never the builder. */
    public List<AgentKind> reviewersFor(AgentKind builder) {
        return Reviewers.candidates(builder, kinds(), policy, quota);
    }

    /**
     * Builds the registry, throttled per vendor according to {@code policy}.
     *
     * <p>Per vendor rather than one shared number, because the two subscriptions are not the same
     * size. Holding the abundant one at the scarce one's limit leaves it idle for no gain.
     *
     * @param claudeModel model alias for Claude, or {@code null} for the CLI default
     * @param codexModel  model slug for Codex, or {@code null} for the user's configured default
     * @param antigravityModel model for Antigravity, or {@code null} for the CLI default.
     *                    Ignored entirely when agy is not installed, which is a supported state.
     */
    public static Agents of(String claudeModel, String codexModel, String antigravityModel,
                            VendorPolicy policy,
                            QuotaLedger quota,
                            UsageLedger usage) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, ThrottledAgent.wrapping(ClaudeAgent.create(claudeModel),
                policy.concurrency(AgentKind.CLAUDE), quota, usage));
        // Codex is told which sandbox to use and which features to leave out on every call: it
        // remembers neither between turns, and what the person's own configuration says is about
        // their use of it, not an unattended build's.
        CodexOptions codex = new CodexOptions(policy.of(AgentKind.CODEX).getWindowsSandbox(),
                policy.of(AgentKind.CODEX).getDisabledFeatures(),
                policy.of(AgentKind.CODEX).getDisabledMcpServers());
        map.put(AgentKind.CODEX, ThrottledAgent.wrapping(CodexAgent.create(codexModel, codex),
                policy.concurrency(AgentKind.CODEX), quota, usage));

        // Optional, unlike the two above. Claude and Codex are what Forge is for, and a missing one
        // is a setup mistake worth refusing to start over. A third vendor that only ever reviews is
        // a different proposition: not having it should cost you a second opinion, not the whole
        // pipeline, and a machine without it must still run every project it already has.
        if (CliLocator.installed(AgentKind.ANTIGRAVITY)) {
            map.put(AgentKind.ANTIGRAVITY, ThrottledAgent.wrapping(
                    AntigravityAgent.create(antigravityModel),
                    policy.concurrency(AgentKind.ANTIGRAVITY), quota, usage));
        }
        return new Agents(map, policy, quota);
    }

    public Agent get(AgentKind kind) {
        Agent agent = agents.get(kind);
        if (agent == null) {
            throw new IllegalStateException("no agent configured for " + kind);
        }
        return agent;
    }
}
