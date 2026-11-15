package com.qts.sdp.workorder.exception;

public class WorkOrderNotFoundException extends RuntimeException {

    public WorkOrderNotFoundException() {
        super("Work order not found");
    }
}
