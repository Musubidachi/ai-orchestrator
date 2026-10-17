package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.Progress;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.status.read.Activity;
import dev.fmangoni.forge.status.read.Seconds;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/** Where it is in the pipeline, and what, if anything, is holding it up. */
@Value
@Builder
public class StatusProgress {

    private final String phase;

    private final String phaseLabel;

    private final int percent;

    private final boolean running;

    private final String activity;

    /**
    * Place in the run queue: 1 is next up, 0 means it is not queued at all.
    *
    * <p>Only counts work that could actually start. A spec waiting on its parent is not
    * "third in line" in any useful sense — it is not in the line.
    */
    private final int queuePosition;

    /** Why it cannot start yet, or blank if nothing is stopping it. */
    private final String blockedReason;

    private final String lastError;

    private final String lastFailingCommand;

    private final String retryAfter;

    private final long retryInSeconds;

    public static StatusProgress of(RunState state, RunPaths paths, boolean running, int queuePosition,
                             String blockedReason, Instant now) {
        Phase phase = state.getPhase();
        // A suspended project shows how far it had got, not how far "suspended" is.
        Phase shown = phase == Phase.SUSPENDED && state.getResumePhase() != null ? state.getResumePhase() : phase;
        // Held for a person, the card's headline is that, not a resume time nobody is waiting for.
        boolean held = state.getNeedsPerson() != null;
        Instant resumes = held ? null : state.getRetryAfter();
        return builder()
                .phase(phase.name())
                .phaseLabel(Progress.label(phase))
                .percent(Progress.percent(shown))
                .running(running)
                .activity(running ? Activity.latest(paths.logs()) : "")
                .queuePosition(queuePosition)
                .blockedReason(held ? "waiting for you — press Start once it is dealt with"
                        : blockedReason == null ? "" : blockedReason)
                .lastError(state.getLastError() == null ? "" : state.getLastError())
                .lastFailingCommand(state.getLastFailure() == null ? "" : state.getLastFailure().command())
                .retryAfter(resumes == null ? "" : resumes.toString())
                .retryInSeconds(resumes == null ? 0 : Math.max(0, Seconds.between(now, resumes)))
                .build();
    }

    public static StatusProgress queued(int queuePosition, String blockedReason) {
        return builder()
                .phase(Phase.NEW.name())
                .phaseLabel(Progress.label(Phase.NEW))
                .activity("")
                .queuePosition(queuePosition)
                .blockedReason(blockedReason == null ? "" : blockedReason)
                .lastError("")
                .lastFailingCommand("")
                .retryAfter("")
                .build();
    }
}
