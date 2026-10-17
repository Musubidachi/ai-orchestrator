/**
 * The prompts for a pipeline turn that is going well: build, improve, continue, and plan.
 *
 * <p><b>Belongs here:</b> One class per prompt, {@code TimeContract} — the budget and handoff
 * contract every turn shares — and {@code RunFileBrief}, the note any turn carries while the
 * project has no {@code run.bat} the gate will accept.
 *
 * <p><b>Not here:</b> Prompts for a turn that exists because something was wrong ({@code
 * brief.repair}), prompts the editor uses before a project exists ({@code brief.draft}), and
 * prompts a capability sends itself ({@code review.ReviewBrief}, {@code
 * security.ai.SecurityBrief}).
 *
 * <p><b>Start with:</b> {@code BuildBrief.of}, {@code PlanBrief.of}, {@code TimeContract}.
 *
 * <p>Depends on: gate, plan, project, spec.model.
 */
package dev.fmangoni.forge.brief;
