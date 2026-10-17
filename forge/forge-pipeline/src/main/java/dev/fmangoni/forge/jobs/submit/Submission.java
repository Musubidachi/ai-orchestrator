package dev.fmangoni.forge.jobs.submit;

import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.state.RunState;
import java.util.List;

/**
 * One submission of the review form, already saved, as {@link SubmissionOutcome} needs it.
 *
 * @param items     what the person was asked to rule on
 * @param merged    every ruling on record for those items, this sitting's merged in
 * @param decisions this sitting's rulings only; empty when acting on feedback left earlier, which
 *                  is what keeps such a delivery from ever accepting
 * @param rulings   every feature ruling on record
 * @param unfilmed  approved features still owed a recording
 * @param kept      what was saved, worded to go in front of any refusal
 */
record Submission(String id, RunPaths paths, RunState state, List<ReviewItem> items, List<Decision> merged,
                  List<Decision> decisions, List<Decision> rulings, List<RecordingTake> unfilmed, String kept) {
}
