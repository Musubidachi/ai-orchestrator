/**
 * The local editor and dashboard: one small HTTP server, bound to loopback.
 *
 * <p><b>Belongs here:</b> {@code UiServer}, which wires the server and nothing else, and serving a
 * run's own files.
 *
 * <p><b>Not here:</b> The plumbing every endpoint goes through ({@code ui.http}), the endpoints
 * ({@code ui.route}), what they parse ({@code ui.request}), the wishlist editor ({@code ui.editor})
 * and drafting ({@code ui.suggest}).
 *
 * <p><b>Start with:</b> {@code UiServer.start}, {@code RequestGuard}.
 *
 * <p>Depends on: agent.ledger, jobs, report.shell, ui.editor, ui.http, ui.route, ui.suggest.
 */
package dev.fmangoni.forge.ui;
