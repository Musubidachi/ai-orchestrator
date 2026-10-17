/**
 * The plumbing every endpoint goes through: registration, request and response, query parameters,
 * and who is allowed to ask.
 *
 * <p><b>Belongs here:</b> {@code Endpoints} (which registers each context as a read or a write, and
 * there is no third way), JSON request and response handling, reading query parameters, and {@code
 * RequestGuard}, which decides whether a request came from the editor itself.
 *
 * <p><b>Not here:</b> Any endpoint's own behaviour. The guard's four checks — Host, Origin,
 * Sec-Fetch-Site, and a JSON POST for anything that changes state — are not details to trade away.
 *
 * <p><b>Start with:</b> {@code Endpoints.read}, {@code Endpoints.write}, {@code
 * RequestGuard.refuse}.
 *
 * <p>Depends on: json.
 */
package dev.fmangoni.forge.ui.http;
