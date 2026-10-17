/**
 * Known vulnerabilities for packages, from OSV.
 *
 * <p><b>Belongs here:</b> The client interface and the HTTP implementation, including how an
 * advisory's severity is read.
 *
 * <p><b>Not here:</b> Deciding which packages to ask about, which is the dependency audit's. OSV is
 * sent package names and versions and nothing else.
 *
 * <p><b>Start with:</b> {@code OsvClient.http}.
 *
 * <p>Depends on: security.model, security.osv.model.
 */
package dev.fmangoni.forge.security.osv;
