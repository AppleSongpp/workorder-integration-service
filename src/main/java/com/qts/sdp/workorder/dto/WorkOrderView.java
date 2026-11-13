package com.qts.sdp.workorder.dto;

import com.qts.sdp.workorder.model.SourceSystem;
import com.qts.sdp.workorder.model.WorkOrder;
import com.qts.sdp.workorder.model.WorkOrderStatus;

/** Immutable snapshot of a work order handed out by the business layer. */
public record WorkOrderView(String id, SourceSystem sourceSystem, String description, WorkOrderStatus status,
        long version) {

    public static WorkOrderView of(WorkOrder workOrder) {
        return new WorkOrderView(workOrder.getExternalId(), workOrder.getSourceSystem(),
                workOrder.getDescription(), workOrder.getStatus(), workOrder.getVersion());
    }
}
