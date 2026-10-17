package dev.fmangoni.forge.cli.support;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.agent.policy.VendorPolicy;
import dev.fmangoni.forge.project.ForgeHome;
import lombok.experimental.UtilityClass;

/** The shared pieces every command builds the same way: the ledgers and the agents. */
@UtilityClass
public class Wiring {

    /** One ledger per root, shared by every path that can spend quota. */
    public QuotaLedger quota(ForgeHome home) {
        return new QuotaLedger(home.root().resolve("quota.json"));
    }

    /** Where measured consumption accumulates, so a budget can eventually be inferred from it. */
    UsageLedger usage(ForgeHome home) {
        return new UsageLedger(home.root().resolve("usage.json"));
    }

    /** The agents, as the options name their models; fails when no CLI is usable. */
    public Agents agents(ForgeHome home, String[] args) {
        return Agents.of(
                Args.option(args, "--claude-model").orElse(null),
                Args.option(args, "--codex-model").orElse(null),
                Args.option(args, "--antigravity-model").orElse(null),
                VendorPolicy.load(home.root()), quota(home), usage(home));
    }

    /**
     * The agents, or {@code null} if the CLIs are not usable.
     *
     * <p>A missing CLI disables suggestions and job control rather than the editor. Writing specs
     * by hand is the primary path and must keep working on a machine where nothing is installed yet.
     */
    public Agents agentsOrNull(ForgeHome home, String[] args) {
        try {
            return agents(home, args);
        } catch (RuntimeException e) {
            System.out.println("Agent CLIs unavailable, so suggestions and job control are off: " + e.getMessage());
            return null;
        }
    }
}
