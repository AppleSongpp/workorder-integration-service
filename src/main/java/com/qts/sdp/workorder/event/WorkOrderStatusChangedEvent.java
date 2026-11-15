package com.qts.sdp.workorder.event;

import java.time.Instant;
import java.util.UUID;

import com.qts.sdp.workorder.model.SourceSystem;
import com.qts.sdp.workorder.model.WorkOrderStatus;

/**
 * Published whenever a work order's status changes.
 *
 * @param eventId          unique per event; consumers de-duplicate on it
 * @param eventType        always {@value #TYPE}
 * @param schemaVersion    version of this payload contract
 * @param occurredAt       when the change was committed (informational; do not order by it)
 * @param workOrderId      public work order id, e.g. {@code WO-1001}
 * @param sourceSystem     system that owns the work order
 * @param previousStatus   status before the change
 * @param newStatus        status after the change
 * @param workOrderVersion version of the work order after the change. Strictly increasing per
 *                         work order, so a consumer that has applied version N ignores any
 *                         event with a version &lt;= N (stale or duplicate)
 * @param changedBy        identity of the authenticated caller that made the change
 */
public record WorkOrderStatusChangedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String workOrderId,
        SourceSystem sourceSystem,
        WorkOrderStatus previousStatus,
        WorkOrderStatus newStatus,
        long workOrderVersion,
        String changedBy) {

    public static final String TYPE = "WorkOrderStatusChanged";
    public static final int SCHEMA_VERSION = 1;
}
