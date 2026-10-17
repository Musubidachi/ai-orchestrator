/**
 * What a person submits from a report, and what follows from it.
 *
 * <p><b>Belongs here:</b> Saving the submission before anything can refuse it, then at most one
 * consequence: sent back, a recording owed, a note to deliver, or accepted. And {@code ReviewDecks},
 * which finds the project a quick review is about, the same way a submission will. And
 * {@code Attachments}, the screenshots a person puts in those notes.
 *
 * <p><b>Not here:</b> The rules about what a decision means, which are {@code review.decision}.
 *
 * <p><b>Start with:</b> {@code Submissions.decide}, {@code SubmissionOutcome.act}.
 *
 * <p>Depends on: brief.repair, feature, git, jobs.queue, jobs.work, json, project, report.deck, review, review.decision, review.decision.model, review.model, security.model, state.
 */
package dev.fmangoni.forge.jobs.submit;
