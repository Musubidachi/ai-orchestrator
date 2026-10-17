package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.state.RunState;
import lombok.Builder;
import lombok.Value;

/** Which project this is, and where it sits among the others. */
@Value
@Builder
public class StatusIdentity {

    private final String id;

    private final String title;

    /** The project this improves, or blank for a project in its own right. */
    private final String parentId;

    /** Lower runs earlier. Shown so the queue order is visible rather than inferred. */
    private final int priority;

    private final String assignedTo;

    public static StatusIdentity of(RunState state, String parentId, int priority) {
        return builder()
                .id(state.getId())
                .title(state.getTitle())
                .parentId(parentId == null ? "" : parentId)
                .priority(priority)
                .assignedTo(state.getAssignedTo() == null ? "" : state.getAssignedTo().name())
                .build();
    }

    public static StatusIdentity queued(String id, String title, String parentId, int priority) {
        return builder()
                .id(id)
                .title(title)
                .parentId(parentId == null ? "" : parentId)
                .priority(priority)
                .assignedTo("")
                .build();
    }
}
