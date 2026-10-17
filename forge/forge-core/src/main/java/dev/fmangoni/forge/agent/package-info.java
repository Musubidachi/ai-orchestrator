/**
 * The registry in front of the coding agents Forge drives.
 *
 * <p><b>Belongs here:</b> {@code Agents}, which resolves a vendor to a configured agent so nothing
 * else constructs one, and the per-vendor concurrency limit that wraps each one.
 *
 * <p><b>Not here:</b> Everything a vendor-specific detail touches: the CLIs are in {@code
 * agent.vendor}, what they print in {@code agent.stream}, what they have left in {@code
 * agent.ledger}, and how they may be used in {@code agent.policy}. What to ask an agent is in
 * {@code brief}, and when to ask it in {@code pipeline} and {@code jobs}. The {@code Agent}
 * contract itself is in {@code agent.model}, so the vendors can implement it without this package
 * and the vendors depending on each other.
 *
 * <p><b>Start with:</b> {@code Agents.get}, {@code Agents.of}.
 *
 * <p>Depends on: agent.ledger, agent.model, agent.policy, agent.stream, agent.vendor, agent.vendor.model.
 */
package dev.fmangoni.forge.agent;
