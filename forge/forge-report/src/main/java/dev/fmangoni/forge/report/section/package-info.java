/**
 * The parts of a project's page that say what happened: the verdict, the approach, what changed,
 * what is not proven, what the checks cost, and the evidence.
 *
 * <p><b>Belongs here:</b> One class per section, each rendering its own HTML from the run's files.
 *
 * <p><b>Not here:</b> Deciding anything. A section shows what other packages recorded.
 *
 * <p><b>Start with:</b> {@code VerdictSection.render} and the other {@code *Section} classes.
 *
 * <p>Depends on: feature, gate, git, project, report.evidence, report.review, report.shell, review.decision, review.decision.model, state.
 */
package dev.fmangoni.forge.report.section;
