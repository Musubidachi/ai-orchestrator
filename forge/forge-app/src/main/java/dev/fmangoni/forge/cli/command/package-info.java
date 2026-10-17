/**
 * One class per subcommand: run, ui, status, report, rank and prune.
 *
 * <p><b>Belongs here:</b> Reading the options a command takes and calling the packages that do the
 * work.
 *
 * <p><b>Not here:</b> Argument parsing itself and the shared wiring, which stay in {@code cli}.
 *
 * <p><b>Start with:</b> {@code RunCommand.run}, {@code UiCommand.run}.
 *
 * <p>Depends on: agent, agent.catalog, agent.model, cli.support, jobs, jobs.unattended, project, report, review, spec, spec.model, spec.rank, spec.tier, state, tour, tour.film,
 * tour.model, tour.show, ui, ui.suggest.
 */
package dev.fmangoni.forge.cli.command;
