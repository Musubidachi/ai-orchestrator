package dev.fmangoni.forge.project;

import lombok.experimental.UtilityClass;

/**
 * Turns a phase into something a progress bar can show.
 *
 * <p>The percentages are honest about being coarse. A project's real cost is dominated by how many
 * repair rounds it needs, which nobody knows in advance, so this reports position in the pipeline
 * rather than pretending to estimate time. A bar that sits at 50% through four repair rounds is
 * telling the truth; one that creeps toward 99% would not be.
 */
@UtilityClass
public class Progress {

    public int percent(Phase phase) {
        return switch (phase) {
            case NEW -> 0;
            case PLANNING -> 5;
            case SCAFFOLDED -> 10;
            case GATING -> 45;
            case REPAIRING -> 35;
            case CONTINUING -> 55;
            case SECURITY_CHECK -> 70;
            case SECURITY_REPAIR -> 62;
            case REVIEWING -> 80;
            case REVIEW_REPAIR -> 88;
            case SUSPENDED -> 0;   // the caller shows the phase it will resume into
            case DONE, ACCEPTED, PARKED -> 100;
        };
    }

    /** What the pipeline is doing, in the reader's terms rather than the state machine's. */
    public String label(Phase phase) {
        return switch (phase) {
            case NEW -> "queued";
            case PLANNING -> "planning the approach";
            case SCAFFOLDED -> "ready to build";
            case GATING -> "running acceptance commands";
            case REPAIRING -> "fixing what the gate caught";
            case CONTINUING -> "still building — more work to do";
            case SECURITY_CHECK -> "checking security";
            case SECURITY_REPAIR -> "fixing security findings";
            case REVIEWING -> "second model reviewing the diff";
            case REVIEW_REPAIR -> "acting on review findings";
            case DONE -> "waiting for your approval";
            case ACCEPTED -> "you accepted it";
            case PARKED -> "needs you";
            case SUSPENDED -> "waiting for quota";
        };
    }
}
