package com.qts.sdp.workorder.model;

import java.util.Set;

/**
 * Work order lifecycle.
 *
 * <pre>
 *   OPEN ──▶ IN_PROGRESS ──▶ COMPLETED (terminal)
 *     ▲           │
 *     └───────────┘  (work released / put back in the queue)
 * </pre>
 */
public enum WorkOrderStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED;

    public Set<WorkOrderStatus> allowedTargets() {
        return switch (this) {
            case OPEN -> Set.of(IN_PROGRESS);
            case IN_PROGRESS -> Set.of(OPEN, COMPLETED);
            case COMPLETED -> Set.of();
        };
    }

    public boolean canTransitionTo(WorkOrderStatus target) {
        return allowedTargets().contains(target);
    }
}
