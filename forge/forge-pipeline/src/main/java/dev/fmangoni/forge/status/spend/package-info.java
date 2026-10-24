/**
 * The machine's token spend: one total across every project, and the models that moved it.
 *
 * <p><b>Belongs here:</b> {@code Spend}, which reads the runs; {@code Totals}, which does the
 * arithmetic; and {@code Spending} with {@code ModelSpend}, the figures the page receives.
 *
 * <p><b>Not here:</b> Anything about a window or what is left in one — that is
 * {@code agent.ledger}, which measures the vendor's limit rather than the work. Anything that acts
 * on the total: it is for a person to look at, and must never reach a brief, choose a model, or be
 * shown to an agent.
 *
 * <p><b>Start with:</b> {@code Spend.of}, then {@code Totals} for how the split and the
 * unattributed remainder are worked out.
 *
 * <p>Depends on: project, state.
 */
package dev.fmangoni.forge.status.spend;
