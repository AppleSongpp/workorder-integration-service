package com.qts.sdp.workorder.exception;

import com.qts.sdp.workorder.model.WorkOrderStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    private final WorkOrderStatus from;
    private final WorkOrderStatus to;

    public InvalidStatusTransitionException(WorkOrderStatus from, WorkOrderStatus to) {
        super("Cannot change status from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public WorkOrderStatus getFrom() {
        return from;
    }

    public WorkOrderStatus getTo() {
        return to;
    }
}
