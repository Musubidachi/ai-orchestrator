/**
 * The session audit: what the agents did while they built the project, read from their own logs.
 *
 * <p><b>Belongs here:</b> Reading the vendors' event streams for commands run and files written
 * ({@code SessionLog}), the rules for what a person must hear about ({@code RiskRules}), and the
 * layer that puts them in the security report ({@code SessionAudit}).
 *
 * <p><b>Not here:</b> Anything that changes a session or the project. This only reads logs, and
 * nothing it finds earns a repair turn: what an agent did cannot be repaired, only ruled on.
 *
 * <p><b>Start with:</b> {@code SessionAudit}, then {@code RiskRules}.
 *
 * <p>Depends on: json, security.model.
 */
package dev.fmangoni.forge.security.session;
