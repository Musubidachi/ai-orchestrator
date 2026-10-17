/**
 * How each vendor should be used when the spec does not say: whether it takes projects of its own,
 * and who is allowed to review whose work.
 *
 * <p><b>Belongs here:</b> {@code VendorPolicy} (read from {@code vendors.json}) and {@code
 * Reviewers}, which orders the models that may review a project.
 *
 * <p><b>Not here:</b> Quota arithmetic, which is the ledger's. The one rule that is not negotiable
 * lives here: a model never reviews what it built.
 *
 * <p><b>Start with:</b> {@code VendorPolicy}, {@code Reviewers.candidates}.
 *
 * <p>Depends on: agent.ledger, agent.model, agent.policy.model, json.
 */
package dev.fmangoni.forge.agent.policy;
