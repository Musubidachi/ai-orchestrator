/**
 * The editor's HTTP endpoints, grouped by what they are about.
 *
 * <p><b>Belongs here:</b> Spec routes, job routes, system routes, and the two pages that are not
 * the dashboard: the quick review and Polish. Each handler parses a request and calls into
 * {@code jobs} or the editor.
 *
 * <p><b>Not here:</b> Any logic of its own. A handler that cannot be understood without HTTP has
 * too much in it.
 *
 * <p><b>Start with:</b> {@code SpecRoutes}, {@code JobRoutes}, {@code SystemRoutes}.
 *
 * <p>Depends on: agent.ledger, agent.model, jobs, jobs.button, jobs.polish, jobs.stop, jobs.work, report.deck, spec, spec.model, spec.rank, spec.tier, status.league, ui.editor, ui.http, ui.request, ui.suggest.
 */
package dev.fmangoni.forge.ui.route;
