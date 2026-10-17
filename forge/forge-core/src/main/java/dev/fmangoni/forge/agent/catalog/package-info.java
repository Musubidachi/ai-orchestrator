/**
 * What each vendor's CLI says this account can actually run.
 *
 * <p><b>Belongs here:</b> {@code ClaudeCatalog} and {@code CodexCatalog}, which read the caches the
 * two CLIs keep for their own model pickers, {@code AntigravityCatalog}, which has no cache and so
 * asks {@code agy}, and {@code VendorModels}, which puts the three together.
 *
 * <p><b>Not here:</b> Which model does which job — that is {@code spec.tier}, which reads this to
 * find out what exists and {@code models.json} to find out what to do with it. Availability is
 * discovered; the ladder is a judgement.
 *
 * <p>Nothing here uses a key or an account of its own. It reads files the CLIs have already written
 * and, for one vendor, runs a listing command — never {@code auth.json} or anything else holding a
 * credential.
 *
 * <p>Two speeds: {@code VendorModels.cached()} touches only files and is safe on a page load;
 * {@code asked()} reaches the network and belongs where a ladder is written.
 *
 * <p><b>Start with:</b> {@code VendorModels.cached}.
 *
 * <p>Depends on: agent.model, json.
 */
package dev.fmangoni.forge.agent.catalog;
