package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import java.nio.file.Files;
import lombok.Builder;
import lombok.Value;

/** What the agent's own handoff said: how far it got and what it planned next. */
@Value
@Builder
public class StatusHandoff {

    private final int sessionsNeeded;

    private final int remainingSteps;

    private final int remainingMinutes;

    private final String nextStep;

    private final String planSummary;

    /**
    * How many things the agent said a passing gate still does not prove.
    *
    * <p>On the card rather than only in the report because approval happens on the card.
    * A button that commits you to the work, offered beside no indication of what was left
    * unchecked, invites a decision nobody actually made.
    */
    private final int notVerifiedCount;

    /** Whether the agent has left a readable description of the project. */
    private final boolean hasDigest;

    public static StatusHandoff of(RunState state, RunPaths paths) {
        Plan plan = state.getPlan() != null && state.getPlan().usable() ? state.getPlan() : null;
        return builder()
                .sessionsNeeded(plan == null ? 0 : plan.getSessionsNeeded())
                .remainingSteps(plan == null ? 0 : plan.getRemaining().size())
                .remainingMinutes(plan == null ? 0 : plan.remainingMinutes())
                .nextStep(plan == null || plan.getRemaining().isEmpty() ? "" : plan.getRemaining().get(0).getStep())
                .planSummary(plan == null ? "" : plan.getSummary())
                .notVerifiedCount(plan == null ? 0 : plan.getNotVerified().size())
                .hasDigest(Files.isRegularFile(paths.digestFile()))
                .build();
    }

    public static StatusHandoff queued() {
        return builder().nextStep("").planSummary("").build();
    }
}
