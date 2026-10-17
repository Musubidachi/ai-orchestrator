/**
 * Design options: every vendor draws a few looks for a project, and a person picks one to build.
 *
 * <p><b>Belongs here:</b> {@code DesignStudio}, which asks the vendors and hands over the choice;
 * {@code DesignBrief}, the prompt; {@code References}, the copy of the project a designer may read;
 * {@code DesignCheck}, which keeps only what can safely be shown; {@code DesignRound}, one request
 * while the vendors answer it in parallel; the board that records what was offered; and
 * {@code PolishRecord}, which keeps what was chosen after the board has been emptied.
 *
 * <p><b>Not here:</b> Building the chosen look. That is an ordinary session on the project, started
 * by {@code jobs.polish} and judged by the gate like any other. Nothing in this package writes into
 * a repository except the one file a person chose.
 *
 * <p><b>Start with:</b> {@code DesignStudio.ask}, {@code DesignStudio.choose}, {@code DesignBoard}.
 *
 * <p>Depends on: agent, agent.model, json, process, project, spec.model.
 */
package dev.fmangoni.forge.design;
