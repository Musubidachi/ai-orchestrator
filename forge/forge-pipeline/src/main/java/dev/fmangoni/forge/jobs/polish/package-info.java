/**
 * The Polish button: asking the vendors for looks, and sending a project back to build the chosen one.
 *
 * <p><b>Belongs here:</b> {@code Polish}, which is both presses: the one that fills the board and
 * the one that acts on a choice; {@code PolishBrief}, the words of the request it sends; and
 * {@code PolishView}, what the page is shown.
 *
 * <p><b>Not here:</b> Drawing or checking the designs ({@code design}), and anything the queue may
 * start on its own. Neither press ever runs unattended.
 *
 * <p><b>Start with:</b> {@code Polish.ask}, {@code Polish.build}.
 *
 * <p>Depends on: agent, design, gate, git, jobs.button, jobs.queue, jobs.stop, jobs.work, project, spec, spec.model, state.
 */
package dev.fmangoni.forge.jobs.polish;
