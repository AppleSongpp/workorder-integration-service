package com.qts.sdp.workorder.dto;

import com.qts.sdp.workorder.model.SourceSystem;
import com.qts.sdp.workorder.model.WorkOrderStatus;

public record WorkOrderResponse(String id, SourceSystem sourceSystem, String description, WorkOrderStatus status,
        long version) {

    public static WorkOrderResponse from(WorkOrderView view) {
        return new WorkOrderResponse(view.id(), view.sourceSystem(), view.description(), view.status(),
                view.version());
    }
}
