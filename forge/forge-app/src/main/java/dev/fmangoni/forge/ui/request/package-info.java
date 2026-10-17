/**
 * The shapes the page sends, one per endpoint that takes a body.
 *
 * <p><b>Belongs here:</b> Plain records parsed from JSON.
 *
 * <p><b>Not here:</b> Any behaviour. A request object that validates itself is doing the route's
 * job.
 *
 * <p><b>Start with:</b> {@code JobRequest}, {@code DecisionRequest}.
 *
 * <p>Depends on: agent.model, review.decision.model.
 */
package dev.fmangoni.forge.ui.request;
