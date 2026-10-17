/**
 * Reading what a CLI actually said: its event stream, its refusals, and what the turn consumed.
 *
 * <p><b>Belongs here:</b> Parsing JSONL events, telling the CLI's own words from the model's
 * ({@code CliWords}), classifying an invocation into an outcome (including the factory that builds
 * a result from one), and reading usage off the vendor's own closing event.
 *
 * <p><b>Not here:</b> Anything that decides what to do about the answer. This package only reports
 * what the CLI said.
 *
 * <p><b>Start with:</b> {@code Signals.classify}, {@code Signals.resultOf}, {@code Usage.from},
 * {@code JsonlEvents}.
 *
 * <p>Depends on: agent.model, process.
 */
package dev.fmangoni.forge.agent.stream;
