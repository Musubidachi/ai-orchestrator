/**
 * The layers that read the project itself: secrets, dependencies, risky code, and the running
 * build.
 *
 * <p><b>Belongs here:</b> One class per layer, each returning a {@code LayerResult} and each
 * survivable on its own.
 *
 * <p><b>Not here:</b> Deciding what to do about what they find, and the AI reading, which is {@code
 * security.ai}. The probe never leaves loopback and secrets are redacted before they reach a
 * report.
 *
 * <p><b>Start with:</b> {@code SecretScan}, {@code DependencyAudit}, {@code StaticAnalysis}, {@code
 * LiveProbe}.
 *
 * <p>Depends on: git, process, process.local, security.layer.model, security.model, security.osv, security.osv.model.
 */
package dev.fmangoni.forge.security.layer;
